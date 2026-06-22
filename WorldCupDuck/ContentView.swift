import SwiftUI
import WebKit

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

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.scrollView.keyboardDismissMode = .interactive
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        func webView(_ webView: WKWebView,
                     decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = navigationAction.request.url,
                  let host = url.host else {
                decisionHandler(.allow)
                return
            }

            // Keep in-app navigation within our own domain; open everything else in Safari.
            if host == "duck.gobet365.win" || host.hasSuffix(".gobet365.win") {
                decisionHandler(.allow)
                return
            }

            decisionHandler(.cancel)
            UIApplication.shared.open(url)
        }
    }
}
