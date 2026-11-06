import AppKit
import WebKit
import Security
import UserNotifications

final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler, NSMenuItemValidation {
    private var workspaceWindows: [ObjectIdentifier: (window: NSWindow, webView: WKWebView)] = [:]
    private var dirtyEditors: Set<ObjectIdentifier> = []
    var window: NSWindow? { NSApp.keyWindow ?? workspaceWindows.values.first?.window }
    var webView: WKWebView? {
        if let keyWindow = NSApp.keyWindow, let workspace = workspaceWindows[ObjectIdentifier(keyWindow)] { return workspace.webView }
        return workspaceWindows.values.first?.webView
    }
    var backend: Process?
    var origin: String?
    var sessionToken: String?
    var quitting = false
    var zoomPercent = WorkspaceZoom.normalized(UserDefaults.standard.integer(forKey: WorkspaceZoom.preferenceKey))

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        let executableDirectory = URL(fileURLWithPath: CommandLine.arguments[0]).deletingLastPathComponent()
        let resources = Bundle.main.resourceURL!
        let bundledIcon = resources.appendingPathComponent("RuntimeBay.icns")
        let iconURL = FileManager.default.fileExists(atPath: bundledIcon.path) ? bundledIcon : executableDirectory.appendingPathComponent("RuntimeBay.icns")
        if let icon = NSImage(contentsOf: iconURL) { NSApp.applicationIconImage = icon }
        makeMenu()
        _ = createWorkspaceWindow()
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        DispatchQueue.global(qos: .userInitiated).async { self.startBackend() }
    }
    @discardableResult
    private func createWorkspaceWindow() -> NSWindow {
        let executableDirectory = URL(fileURLWithPath: CommandLine.arguments[0]).deletingLastPathComponent()
        let resources = Bundle.main.resourceURL!
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1280, height: 850),
            styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.title = "Runtime Bay"
        window.minSize = NSSize(width: 960, height: 660)
        window.center()
        let config = WKWebViewConfiguration()
        config.userContentController.add(self, name: "runtimeBay")
        config.userContentController.addUserScript(WKUserScript(source: "window.runtimeBayNative = true;", injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let workspaceView = WorkspaceWebView(frame: window.contentView!.bounds, configuration: config)
        workspaceView.zoomCommand = { [weak self] direction in self?.changeZoom(direction) }
        workspaceView.pageZoom = Double(zoomPercent) / 100
        workspaceView.autoresizingMask = [.width, .height]
        workspaceView.navigationDelegate = self
        workspaceView.uiDelegate = self
        window.contentView = workspaceView
        window.delegate = self
        workspaceWindows[ObjectIdentifier(window)] = (window, workspaceView)
        let webRoot = ProcessInfo.processInfo.environment["RUNTIMEBAY_WEB_ROOT"]
        let logoURL = webRoot.map { URL(fileURLWithPath: $0).appendingPathComponent("assets/runtime-bay.png") }
            ?? resources.appendingPathComponent("runtime-bay.png")
        let logo = (try? Data(contentsOf: logoURL))?.base64EncodedString() ?? ""
        let visualResources = FileManager.default.fileExists(atPath: resources.appendingPathComponent("loading-startup.js").path) ? resources : executableDirectory
        let loadingCSS = (try? String(contentsOf: visualResources.appendingPathComponent("loading.css"), encoding: .utf8)) ?? ""
        let loadingScript = (try? String(contentsOf: visualResources.appendingPathComponent("loading-startup.js"), encoding: .utf8)) ?? ""
        workspaceView.loadHTMLString("""
            <!doctype html><html lang="en"><head><meta name="color-scheme" content="light dark"><style>
            :root{--paper:#f6f6f0;--ink:#203238;--accent:#167d70;--focus:#3d9e8b;--runtime-bay-logo:url('data:image/png;base64,\(logo)')}
            body{margin:0;min-height:100vh;display:grid;place-content:center;background:var(--paper);color:var(--ink);font:14px -apple-system}
            @media(prefers-color-scheme:dark){:root{--paper:#101615;--ink:#eaf1ef;--accent:#7bd2bc;--focus:#b2e7da}}
            \(loadingCSS)
            </style></head><body><main><h1>Runtime Bay</h1></main><script>\(loadingScript)</script></body></html>
            """, baseURL: nil)
        if origin != nil { loadWorkspace(in: workspaceView) }
        return window
    }
    private func loadWorkspace(in webView: WKWebView) {
        guard let origin, let sessionToken else { return }
        webView.configuration.userContentController.addUserScript(WKUserScript(source: "window.runtimeBaySession = '\(sessionToken)';", injectionTime: .atDocumentStart, forMainFrameOnly: true))
        webView.load(URLRequest(url: URL(string: origin + "/")!))
    }
    func makeMenu() {
        let main = NSMenu()
        let item = NSMenuItem()
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "About Runtime Bay", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: "")
        let settings = NSMenuItem(title: "Appearance…", action: #selector(customizeAppearance), keyEquivalent: ",")
        settings.target = self
        appMenu.addItem(settings)
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "Hide Runtime Bay", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        let hideOthers = appMenu.addItem(withTitle: "Hide Others", action: #selector(NSApplication.hideOtherApplications(_:)), keyEquivalent: "h")
        hideOthers.keyEquivalentModifierMask = [.command, .option]
        appMenu.addItem(withTitle: "Show All", action: #selector(NSApplication.unhideAllApplications(_:)), keyEquivalent: "")
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "Quit Runtime Bay", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        item.submenu = appMenu
        main.addItem(item)
        let file = NSMenuItem(title: "File", action: nil, keyEquivalent: "")
        let fileMenu = NSMenu(title: "File")
        fileMenu.addItem(commandItem("New…", #selector(importWorkflow), "o"))
        fileMenu.addItem(commandItem("New Window", #selector(newWindow), "n", [.command, .shift]))
        fileMenu.addItem(commandItem("New Terminal", #selector(newTerminal), "t"))
        fileMenu.addItem(.separator())
        fileMenu.addItem(withTitle: "Close Window", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        file.submenu = fileMenu
        main.addItem(file)
        let edit = NSMenuItem(title: "Edit", action: nil, keyEquivalent: "")
        let editMenu = NSMenu(title: "Edit")
        editMenu.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
        let redo = editMenu.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "z")
        redo.keyEquivalentModifierMask = [.command, .shift]
        editMenu.addItem(.separator())
        editMenu.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        editMenu.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        editMenu.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        editMenu.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        edit.submenu = editMenu
        main.addItem(edit)
        let view = NSMenuItem(title: "View", action: nil, keyEquivalent: "")
        let viewMenu = NSMenu(title: "View")
        let reload = NSMenuItem(title: "Reload Workspace", action: #selector(reloadWorkspace), keyEquivalent: "r")
        reload.target = self
        viewMenu.addItem(reload)
        viewMenu.addItem(.separator())
        viewMenu.addItem(commandItem("Zoom In", #selector(zoomIn), "+"))
        viewMenu.addItem(commandItem("Zoom Out", #selector(zoomOut), "-"))
        viewMenu.addItem(commandItem("Actual Size", #selector(resetZoom), "0"))
        viewMenu.addItem(.separator())
        viewMenu.addItem(commandItem("Previous Terminal", #selector(previousTerminal), "[", [.command, .shift]))
        viewMenu.addItem(commandItem("Next Terminal", #selector(nextTerminal), "]", [.command, .shift]))
        viewMenu.addItem(.separator())
        let fullScreen = viewMenu.addItem(withTitle: "Toggle Full Screen", action: #selector(NSWindow.toggleFullScreen(_:)), keyEquivalent: "f")
        fullScreen.keyEquivalentModifierMask = [.command, .control]
        view.submenu = viewMenu
        main.addItem(view)
        let windowItem = NSMenuItem(title: "Window", action: nil, keyEquivalent: "")
        let windowMenu = NSMenu(title: "Window")
        windowMenu.addItem(withTitle: "Minimize", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m")
        windowMenu.addItem(withTitle: "Zoom", action: #selector(NSWindow.performZoom(_:)), keyEquivalent: "")
        windowMenu.addItem(.separator())
        windowMenu.addItem(withTitle: "Bring All to Front", action: #selector(NSApplication.arrangeInFront(_:)), keyEquivalent: "")
        windowItem.submenu = windowMenu
        main.addItem(windowItem)
        NSApp.windowsMenu = windowMenu
        NSApp.mainMenu = main
    }
    func commandItem(_ title: String, _ action: Selector, _ key: String, _ modifiers: NSEvent.ModifierFlags = .command) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: key)
        item.target = self
        item.keyEquivalentModifierMask = modifiers
        return item
    }
    func changeZoom(_ direction: Int) {
        zoomPercent = direction == 0 ? 100 : WorkspaceZoom.next(zoomPercent, direction: direction)
        for workspace in workspaceWindows.values { workspace.webView.pageZoom = Double(zoomPercent) / 100 }
        UserDefaults.standard.set(zoomPercent, forKey: WorkspaceZoom.preferenceKey)
        // xterm renders into a canvas: explicitly refit it when page zoom changes.
        for workspace in workspaceWindows.values { workspace.webView.evaluateJavaScript("window.dispatchEvent(new Event('resize'))") }
    }
    @objc func zoomIn() { changeZoom(1) }
    @objc func zoomOut() { changeZoom(-1) }
    @objc func resetZoom() { changeZoom(0) }
    func validateMenuItem(_ menuItem: NSMenuItem) -> Bool {
        if menuItem.action == #selector(zoomIn) { return zoomPercent < WorkspaceZoom.range.upperBound }
        if menuItem.action == #selector(zoomOut) { return zoomPercent > WorkspaceZoom.range.lowerBound }
        if menuItem.action == #selector(resetZoom) { return zoomPercent != 100 }
        return webView != nil
    }
    func workspaceCommand(_ command: String) {
        webView?.evaluateJavaScript("window.dispatchEvent(new CustomEvent('runtimebay:command', {detail: '\(command)'}))")
    }
    @objc func importWorkflow() { workspaceCommand("import-workflow") }
    @objc func newWindow() {
        let newWindow = createWorkspaceWindow()
        newWindow.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }
    @objc func newTerminal() { workspaceCommand("new-terminal") }
    @objc func previousTerminal() { workspaceCommand("previous-terminal") }
    @objc func nextTerminal() { workspaceCommand("next-terminal") }
    @objc func reloadWorkspace() {
        guard let target = webView else { return }
        if let window = target.window, dirtyEditors.contains(ObjectIdentifier(window)) && !confirmDiscardEdits() { return }
        target.evaluateJavaScript("Boolean(document.querySelector('.loading-overlay'))") { result, _ in
            if result as? Bool != true { target.reload() }
        }
    }
    @objc func customizeAppearance() { webView?.evaluateJavaScript("window.dispatchEvent(new Event('runtimebay:customize'))") }
    func windowWillClose(_ notification: Notification) {
        guard let window = notification.object as? NSWindow else { return }
        dirtyEditors.remove(ObjectIdentifier(window))
        workspaceWindows.removeValue(forKey: ObjectIdentifier(window))
    }
    func windowShouldClose(_ sender: NSWindow) -> Bool {
        return quitting || !dirtyEditors.contains(ObjectIdentifier(sender)) || confirmDiscardEdits()
    }
    private func confirmDiscardEdits() -> Bool {
        let alert = NSAlert()
        alert.messageText = "Discard unsaved editor changes?"
        alert.informativeText = "Save your files before closing if you want to keep these changes."
        alert.addButton(withTitle: "Keep editing")
        alert.addButton(withTitle: "Discard changes")
        return alert.runModal() == .alertSecondButtonReturn
    }
    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let alert = NSAlert(); alert.messageText = message
        alert.addButton(withTitle: "OK"); alert.addButton(withTitle: "Cancel")
        completionHandler(alert.runModal() == .alertFirstButtonReturn)
    }
    func startBackend() {
        do {
            let env = ProcessInfo.processInfo.environment
            let resources = Bundle.main.resourceURL!
            let jar = env["RUNTIMEBAY_JAR"] ?? resources.appendingPathComponent("runtimebay-backend.jar").path
            let launcher = env["RUNTIMEBAY_LAUNCHER"] ?? resources.appendingPathComponent("runtimebay-launcher").path
            let webRoot = env["RUNTIMEBAY_WEB_ROOT"] ?? resources.appendingPathComponent("web").path
            let bundledJava = resources.appendingPathComponent("jre/bin/java").path
            var java = env["RUNTIMEBAY_JAVA"] ?? bundledJava
            if !FileManager.default.isExecutableFile(atPath: java) {
                let lookup = Process()
                let output = Pipe()
                lookup.executableURL = URL(fileURLWithPath: "/usr/libexec/java_home")
                lookup.arguments = ["-v", "25"]
                lookup.standardOutput = output
                try lookup.run()
                let home = String(data: output.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                lookup.waitUntilExit()
                guard lookup.terminationStatus == 0 && !home.isEmpty else { throw NSError(domain: "Runtime Bay", code: 1, userInfo: [NSLocalizedDescriptionKey: "Install JDK 25 for development, or use a packaged Runtime Bay build."]) }
                java = home + "/bin/java"
            }
            var bytes = [UInt8](repeating: 0, count: 32)
            guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw NSError(domain: "Runtime Bay", code: 2) }
            let token = bytes.map { String(format: "%02x", $0) }.joined()
            sessionToken = token
            let process = Process()
            let output = Pipe()
            process.executableURL = URL(fileURLWithPath: java)
            process.arguments = ["-jar", jar]
            var childEnv = env
            childEnv["RUNTIMEBAY_TOKEN"] = token
            childEnv["RUNTIMEBAY_WEB_ROOT"] = webRoot
            childEnv["RUNTIMEBAY_LAUNCHER"] = launcher
            childEnv["PATH"] = "/opt/homebrew/bin:/usr/local/bin:" + (env["PATH"] ?? "/usr/bin:/bin:/usr/sbin:/sbin")
            process.environment = childEnv
            process.standardOutput = output
            process.standardError = FileHandle.standardError
            process.terminationHandler = { _ in
                DispatchQueue.main.async {
                    if !self.quitting { self.showError("The runtime engine stopped. Close Runtime Bay and reopen it to reconnect.") }
                }
            }
            backend = process
            try process.run()
            let timeout = DispatchWorkItem { if self.origin == nil { self.showError("The runtime engine did not start within 15 seconds."); if process.isRunning { process.terminate() } } }
            DispatchQueue.main.asyncAfter(deadline: .now() + 15, execute: timeout)
            var line = Data()
            while line.count < 4096 {
                let byte = output.fileHandleForReading.readData(ofLength: 1)
                if byte.isEmpty || byte.first == 10 { break }
                line.append(byte)
            }
            guard let ready = String(data: line, encoding: .utf8), ready.hasPrefix("RUNTIME_BAY_READY:"),
                  let port = Int(ready.dropFirst("RUNTIME_BAY_READY:".count)), (1...65535).contains(port) else {
                throw NSError(domain: "Runtime Bay", code: 3, userInfo: [NSLocalizedDescriptionKey: "Could not connect to the runtime engine. Check the launch output."])
            }
            timeout.cancel()
            DispatchQueue.main.async {
                self.origin = "http://127.0.0.1:\(port)"
                for workspace in self.workspaceWindows.values { self.loadWorkspace(in: workspace.webView) }
            }
            // Drain output so the engine can always write diagnostics without blocking.
            _ = output.fileHandleForReading.readDataToEndOfFile()
        } catch { DispatchQueue.main.async { self.showError(error.localizedDescription) } }
    }
    func showError(_ message: String) {
        let alert = NSAlert()
        alert.messageText = "Runtime Bay needs attention"
        alert.informativeText = message
        alert.addButton(withTitle: "OK")
        if let window { alert.beginSheetModal(for: window) } else { alert.runModal() }
    }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.frameInfo.isMainFrame, let url = message.frameInfo.request.url,
              let origin = origin, url.absoluteString.hasPrefix(origin + "/") else { return }
        if let value = message.body as? [String: String], value["action"] == "editorDirty", let window = message.webView?.window {
            if value["dirty"] == "true" { dirtyEditors.insert(ObjectIdentifier(window)) }
            else { dirtyEditors.remove(ObjectIdentifier(window)) }
            return
        }
        if let value = message.body as? [String: String], value["action"] == "appearance" {
            switch value["mode"] {
            case "light": NSApp.appearance = NSAppearance(named: .aqua)
            case "dark": NSApp.appearance = NSAppearance(named: .darkAqua)
            case "system": NSApp.appearance = nil
            default: break
            }
            return
        }
        if let value = message.body as? [String: String], value["action"] == "tunnelFailure" {
            let title = String((value["title"] ?? "Tunnel failed").prefix(100))
            let body = String((value["body"] ?? "An SSH tunnel exited unexpectedly.").prefix(500))
            let identifier = value["id"] ?? UUID().uuidString
            notifyTunnelFailure(identifier: identifier, title: title, body: body)
            return
        }
        guard let action = message.body as? String, action == "chooseFolder" else { return }
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.prompt = "Import project"
        guard let parentWindow = window, let targetWebView = webView else { return }
        panel.beginSheetModal(for: parentWindow) { response in
            guard response == .OK, let path = panel.url?.path,
                  let data = try? JSONSerialization.data(withJSONObject: [path]),
                  let json = String(data: data, encoding: .utf8) else { return }
            targetWebView.evaluateJavaScript("window.dispatchEvent(new CustomEvent('runtimebay:folder', {detail: \(json)[0]}))")
        }
    }
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { decisionHandler(.cancel); return }
        if url.absoluteString == "about:blank" || (origin != nil && url.absoluteString.hasPrefix(origin! + "/")) { decisionHandler(.allow); return }
        if action.navigationType == .linkActivated && ["http", "https"].contains(url.scheme ?? "") { NSWorkspace.shared.open(url) }
        decisionHandler(.cancel)
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows: Bool) -> Bool {
        if hasVisibleWindows {
            sender.arrangeInFront(nil)
        } else {
            let window = createWorkspaceWindow()
            window.makeKeyAndOrderFront(nil)
        }
        sender.activate(ignoringOtherApps: true)
        return true
    }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if quitting { return .terminateNow }
        if !dirtyEditors.isEmpty && !confirmDiscardEdits() { return .terminateCancel }
        quitting = true
        guard let process = backend, process.isRunning else { return .terminateNow }
        process.terminate()
        DispatchQueue.global().async {
            // Closing the Java engine triggers its hook; launcher watchdogs cover an engine crash.
            let deadline = Date().addingTimeInterval(10)
            while process.isRunning && Date() < deadline { Thread.sleep(forTimeInterval: 0.05) }
            if process.isRunning { kill(process.processIdentifier, SIGKILL) }
            DispatchQueue.main.async { sender.reply(toApplicationShouldTerminate: true) }
        }
        return .terminateLater
    }
    private func notifyTunnelFailure(identifier: String, title: String, body: String) {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else { return }
            let content = UNMutableNotificationContent()
            content.title = "Tunnel disconnected · \(title)"
            content.body = body
            content.sound = .default
            let request = UNNotificationRequest(identifier: "runtimebay-tunnel-\(identifier)-\(UUID().uuidString)", content: content, trigger: nil)
            center.add(request)
        }
    }
}
@main
struct RuntimeBay {
    static func main() {
        let app = NSApplication.shared
        let delegate = AppDelegate()
        app.delegate = delegate
        withExtendedLifetime(delegate) { app.run() }
    }
}
