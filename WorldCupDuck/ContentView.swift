import SwiftUI
import WebKit
import Photos

// Background videos bundled inside the app so they play instantly offline
// instead of streaming /assets/*.mp4 from the network on every launch.
private let kBundledVideos: Set<String> = ["fifa26-hero.mp4", "fifa26-analysis.mp4"]

struct ContentView: View {
    var body: some View {
        AppWebView(url: URL(string: "https://duck.gobet365.win/")!)
            .ignoresSafeArea()
    }
}

struct AppWebView: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.allowsInlineMediaPlayback = true

        // Serve the bundled background videos through a custom scheme.
        configuration.setURLSchemeHandler(BundledVideoSchemeHandler(), forURLScheme: "duckasset")

        // Inject the native bridge + video-source rewrite before page scripts run.
        let controller = WKUserContentController()
        controller.addUserScript(WKUserScript(source: Self.bootstrapJS,
                                              injectionTime: .atDocumentStart,
                                              forMainFrameOnly: false))
        controller.add(context.coordinator, name: "duckSaveImage")
        controller.add(context.coordinator, name: "duckCapture")
        configuration.userContentController = controller

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.scrollView.keyboardDismissMode = .interactive

        // Dark base so the rubber-band overscroll area never flashes white.
        let dark = UIColor(red: 2.0 / 255, green: 4.0 / 255, blue: 10.0 / 255, alpha: 1)
        webView.isOpaque = false
        webView.backgroundColor = dark
        webView.scrollView.backgroundColor = dark

        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator() }

    // (1) expose window.DuckNativeSaveImage for the 保存页面截图 button,
    // (2) rewrite background <video>/<source> URLs to the bundled custom scheme.
    static let bootstrapJS = """
    (function () {
      window.DuckNativeSaveImage = function (b64, name) {
        try {
          window.webkit.messageHandlers.duckSaveImage.postMessage({ data: b64, name: name || 'image.png' });
        } catch (e) {}
      };
      window.DuckNativeCapture = function (name) {
        try {
          window.webkit.messageHandlers.duckCapture.postMessage({ name: name || 'image.png' });
        } catch (e) {}
      };
      var FILES = ['fifa26-hero.mp4', 'fifa26-analysis.mp4'];
      function localize(u) {
        if (!u || u.indexOf('duckasset://') === 0) return u;
        for (var i = 0; i < FILES.length; i++) {
          if (u.indexOf(FILES[i]) !== -1) return 'duckasset://media/' + FILES[i];
        }
        return u;
      }
      function rewrite(scope) {
        if (!scope || !scope.querySelectorAll) return;
        var nodes = scope.querySelectorAll('video, source');
        for (var i = 0; i < nodes.length; i++) {
          var el = nodes[i];
          var src = el.getAttribute('src');
          var next = localize(src);
          if (next && next !== src) {
            el.setAttribute('src', next);
            var video = el.tagName === 'VIDEO' ? el : el.parentNode;
            if (video && video.tagName === 'VIDEO') { try { video.load(); } catch (e) {} }
          }
        }
      }
      function sweep() { try { rewrite(document); } catch (e) {} }
      var mo = new MutationObserver(function (muts) {
        for (var i = 0; i < muts.length; i++) {
          var added = muts[i].addedNodes;
          for (var j = 0; j < added.length; j++) {
            var n = added[j];
            if (n.nodeType !== 1) continue;
            if (n.tagName === 'VIDEO' || n.tagName === 'SOURCE') rewrite(n.parentNode || document);
            else rewrite(n);
          }
        }
      });
      try { mo.observe(document.documentElement, { childList: true, subtree: true }); } catch (e) {}
      document.addEventListener('DOMContentLoaded', sweep);
      sweep();
    })();
    """

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        func webView(_ webView: WKWebView,
                     decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = navigationAction.request.url, let host = url.host else {
                decisionHandler(.allow); return
            }
            // Keep in-app navigation within our own domain; open everything else in Safari.
            if host == "duck.gobet365.win" || host.hasSuffix(".gobet365.win") {
                decisionHandler(.allow); return
            }
            decisionHandler(.cancel)
            UIApplication.shared.open(url)
        }

        // Full-page screenshot via scroll-and-stitch: snapshot the viewport, scroll down one
        // viewport, snapshot again, and compose the strips into one tall image. Expanding the
        // WKWebView frame doesn't work (SwiftUI overrides it; takeSnapshot won't capture past the
        // visible area), so we scroll — WKWebView renders each position correctly.
        private func captureFullPage(_ webView: WKWebView) {
            let scrollView = webView.scrollView
            let width = scrollView.bounds.width
            let viewportH = scrollView.bounds.height
            let totalH = scrollView.contentSize.height
            let savedOffset = scrollView.contentOffset

            // Short page -> a single viewport snapshot is enough.
            guard viewportH > 0, width > 0, totalH > viewportH + 1 else {
                webView.takeSnapshot(with: WKSnapshotConfiguration()) { [weak self] image, _ in
                    if let image = image { self?.saveToPhotos(image) }
                }
                return
            }

            let cappedH = min(totalH, viewportH * 12) // guard against extreme pages
            let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: cappedH))
            var strips: [(CGFloat, UIImage)] = []

            func snapStrip(at targetY: CGFloat) {
                scrollView.setContentOffset(CGPoint(x: 0, y: targetY), animated: false)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) {
                    let config = WKSnapshotConfiguration()
                    config.rect = CGRect(x: 0, y: 0, width: width, height: viewportH)
                    if #available(iOS 13.0, *) { config.afterScreenUpdates = true }
                    webView.takeSnapshot(with: config) { [weak self] image, _ in
                        let actualY = scrollView.contentOffset.y
                        if let image = image { strips.append((actualY, image)) }
                        let next = actualY + viewportH
                        if next < cappedH - 1 {
                            snapStrip(at: min(next, cappedH - viewportH))
                        } else {
                            let full = renderer.image { _ in
                                for (oy, img) in strips {
                                    img.draw(in: CGRect(x: 0, y: oy, width: width, height: viewportH))
                                }
                            }
                            scrollView.setContentOffset(savedOffset, animated: false)
                            self?.saveToPhotos(full)
                        }
                    }
                }
            }
            snapStrip(at: 0)
        }

        // Save a UIImage to the Photos library (add-only authorization).
        private func saveToPhotos(_ image: UIImage) {
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
                guard status == .authorized || status == .limited else { return }
                PHPhotoLibrary.shared().performChanges({
                    PHAssetChangeRequest.creationRequestForAsset(from: image)
                }, completionHandler: nil)
            }
        }

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            // Native full-page screenshot of the webview -> Photos.
            if message.name == "duckCapture" {
                guard let webView = message.webView else { return }
                captureFullPage(webView)
                return
            }
            // Receive a base64 PNG from the web screenshot button and save it to Photos.
            if message.name == "duckSaveImage",
               let body = message.body as? [String: Any],
               let b64 = body["data"] as? String,
               let data = Data(base64Encoded: b64),
               let image = UIImage(data: data) {
                saveToPhotos(image)
            }
        }
    }
}

// Serves bundled MP4 files (with HTTP range support) for the duckasset:// scheme.
final class BundledVideoSchemeHandler: NSObject, WKURLSchemeHandler {
    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        let url = urlSchemeTask.request.url
        let name = url?.lastPathComponent ?? ""
        let base = (name as NSString).deletingPathExtension
        guard kBundledVideos.contains(name),
              let path = Bundle.main.path(forResource: base, ofType: "mp4"),
              let handle = FileHandle(forReadingAtPath: path) else {
            urlSchemeTask.didFailWithError(NSError(domain: "duckasset", code: 404, userInfo: nil))
            return
        }
        do {
            let total = Int(try handle.seekToEnd())
            var start = 0
            var end = total - 1
            var status = 200
            if let rangeHeader = urlSchemeTask.request.value(forHTTPHeaderField: "Range"),
               let range = Self.parseRange(rangeHeader, total: total) {
                start = range.lowerBound; end = range.upperBound - 1; status = 206
            }
            try handle.seek(toOffset: UInt64(start))
            let data = try handle.read(upToCount: end - start + 1) ?? Data()
            try? handle.close()

            var headers = [
                "Content-Type": "video/mp4",
                "Accept-Ranges": "bytes",
                "Content-Length": String(data.count),
                "Access-Control-Allow-Origin": "*"
            ]
            if status == 206 { headers["Content-Range"] = "bytes \(start)-\(end)/\(total)" }

            let response = HTTPURLResponse(url: url!, statusCode: status,
                                           httpVersion: "HTTP/1.1", headerFields: headers)!
            urlSchemeTask.didReceive(response)
            urlSchemeTask.didReceive(data)
            urlSchemeTask.didFinish()
        } catch {
            try? handle.close()
            urlSchemeTask.didFailWithError(error)
        }
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {}

    private static func parseRange(_ header: String, total: Int) -> Range<Int>? {
        guard header.hasPrefix("bytes=") else { return nil }
        let parts = header.dropFirst("bytes=".count).split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
        guard let first = parts.first else { return nil }
        let start = Int(first) ?? 0
        let end: Int
        if parts.count > 1, !parts[1].isEmpty, let e = Int(parts[1]) { end = min(e, total - 1) } else { end = total - 1 }
        guard start >= 0, start <= end, start < total else { return nil }
        return start..<(end + 1)
    }
}
