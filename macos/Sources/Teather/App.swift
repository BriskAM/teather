import AppKit
import SwiftUI
import UserNotifications
import CryptoKit
import TeatherCore
import TeatherTransport
import ServiceManagement

final class AppModel: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    @Published var status = "Starting…"
    @Published var phone = ""
    @Published var count = 0
    @Published var notificationsAllowed = false
    @Published var isPaused = false
    @Published var lastSync: Date?
    @Published var lastError = ""
    @Published var launchAtLogin = SMAppService.mainApp.status == .enabled
    @Published var address = ""
    @Published var revealKey = false
    @Published var resetAlert = false
    @Published var sharedFolder = ""
    @Published var shareWritable = false
    @Published var mountedFolder: URL?
    @Published var mounting = false
    @Published var folderMessage = "Enable folder sharing in the Android app."
    private var phoneAddress = ""
    private var sharePath = "/Kunnect/"
    private(set) var token = ""
    private let server = BridgeServer()
    private var mirror = NotificationMirror()
    private let center = UNUserNotificationCenter.current()
    var connected: Bool { status.hasPrefix("Connected") }
    override init() {
        super.init()
        address = Self.tailnetAddress()
        center.delegate = self
        let dismiss = UNNotificationAction(identifier: "dismissBoth", title: "Dismiss on both devices", options: [])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: "mirror", actions: [dismiss], intentIdentifiers: [], options: [.customDismissAction]),
            UNNotificationCategory(identifier: "local", actions: [], intentIdentifiers: [], options: [.customDismissAction])
        ])
        server.onStatus = { [weak self] status in
            self?.status = status
            if !status.hasPrefix("Connected") { self?.sharedFolder = "" }
        }
        server.onPeer = { [weak self] in self?.phoneAddress = $0 }
        server.onPair = { UserDefaults.standard.set($0, forKey: "pairedDevice") }
        server.onMessage = { [weak self] in self?.receive($0) }
        // Notification Center persists across launches; stale mirrors should not.
        center.removeAllDeliveredNotifications()
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didUnmountNotification, object: nil, queue: .main) { [weak self] event in
            if let path = event.userInfo?[NSWorkspace.volumeURLUserInfoKey] as? URL, path == self?.mountedFolder { self?.mountedFolder = nil }
        }
        refreshPermission()
        do { token = try PairingKey.load(); start() } catch { status = "Pairing key unavailable"; lastError = error.localizedDescription }
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in self?.refreshAddress() }
    }
    func mountFolder() {
        guard connected, !sharedFolder.isEmpty, !mounting else { return }
        if let mountedFolder { NSWorkspace.shared.open(mountedFolder); return }
        mounting = true
        let host = phoneAddress, secret = token, readOnly = !shareWritable, path = sharePath
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let path = try NetworkDisk.mount(host: host, secret: secret, readOnly: readOnly, path: path)
                DispatchQueue.main.async { self.mounting = false; self.mountedFolder = path; NSWorkspace.shared.open(path) }
            } catch { DispatchQueue.main.async { self.mounting = false; self.lastError = error.localizedDescription } }
        }
    }
    func ejectFolder() {
        guard let path = mountedFolder else { return }
        DispatchQueue.global(qos: .userInitiated).async {
            do { try NSWorkspace.shared.unmountAndEjectDevice(at: path); DispatchQueue.main.async { self.mountedFolder = nil } }
            catch { DispatchQueue.main.async { self.lastError = error.localizedDescription } }
        }
    }
    func refreshAddress() { address = Self.tailnetAddress() }
    func start() { guard token.count == 64 else { return }; isPaused = false; server.start(token: token, device: UserDefaults.standard.string(forKey: "pairedDevice") ?? "") }
    func togglePause() {
        if isPaused { start() } else { isPaused = true; server.stop() }
    }
    func refreshPermission() { center.getNotificationSettings { settings in DispatchQueue.main.async { self.notificationsAllowed = settings.authorizationStatus == .authorized } } }
    func askPermission() {
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, error in
            DispatchQueue.main.async { if let error { self.lastError = error.localizedDescription }; self.refreshPermission() }
        }
    }
    func login(_ enabled: Bool) {
        do { if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }; launchAtLogin = SMAppService.mainApp.status == .enabled }
        catch { lastError = error.localizedDescription }
    }
    func resetPairing() {
        do {
            let replacement = try PairingKey.regenerate()
            server.stop(); token = replacement; UserDefaults.standard.removeObject(forKey: "pairedDevice")
            _ = mirror.clear(); count = 0; center.removeAllDeliveredNotifications(); start()
        } catch { lastError = "Couldn’t reset pairing: \(error.localizedDescription)" }
    }
    private func receive(_ event: [String: Any]) {
        guard let type = event["type"] as? String else { return }
        if type == "fileShare" {
            sharedFolder = event["enabled"] as? Bool == true ? String((event["name"] as? String ?? "Android folder").prefix(128)) : ""
            shareWritable = event["writable"] as? Bool == true
            sharePath = event["path"] as? String == "/Teather/" ? "/Teather/" : "/Kunnect/"
            folderMessage = sharedFolder.isEmpty ? "Enable folder sharing in the Android app." : (shareWritable ? "Read & write · remount after changing access on Android." : "Read-only · files are fetched on demand.")
            return
        }
        if type == "snapshot" || type == "delta" {
            guard let raw = event["items"], let data = try? JSONSerialization.data(withJSONObject: raw), let items = try? JSONDecoder().decode([NotificationItem].self, from: data), items.allSatisfy(\.valid), Set(items.map(\.key)).count == items.count else { lastError = "Phone sent an invalid notification update"; return }
            apply(mirror.sync(items, removed: event["removed"] as? [String] ?? [], full: type == "snapshot"))
            count = mirror.items.count; lastSync = Date()
        } else if type == "dismissResult", let key = event["key"] as? String, let revision = event["revision"] as? String {
            let result = event["result"] as? String ?? ""
            mirror.resolve(key, revision: revision, result: result)
            if result == "protected" { lastError = "Android kept a protected notification. It was dismissed locally." }
            if result == "stale" { lastError = "A newer notification arrived; it was kept on your phone." }
        } else if type == "test" { testNotification() }
        else if type == "unavailable" { lastError = "Enable Teather’s notification access on your phone." }
    }
    private func apply(_ effects: [SyncEffect]) {
        for effect in effects {
            switch effect {
            case .present(let item, let silent):
                let content = UNMutableNotificationContent()
                content.title = item.title.isEmpty ? item.app : item.title
                content.subtitle = item.app
                content.body = item.body
                content.categoryIdentifier = item.dismissible ? "mirror" : "local"
                content.threadIdentifier = item.package
                content.userInfo = ["key": item.key, "revision": item.revision]
                if !silent && item.sound { content.sound = .default }
                let request = UNNotificationRequest(identifier: Self.notificationID(item.key), content: content, trigger: nil)
                center.add(request) { error in if let error { DispatchQueue.main.async { self.lastError = error.localizedDescription } } }
            case .remove(let key): center.removeDeliveredNotifications(withIdentifiers: [Self.notificationID(key)]); center.removePendingNotificationRequests(withIdentifiers: [Self.notificationID(key)])
            case .dismiss(let key, let revision): server.send(["type": "dismiss", "key": key, "revision": revision])
            }
        }
    }
    func testNotification() {
        let content = UNMutableNotificationContent(); content.title = "You’re connected."; content.body = "Teather can show notifications on this Mac."; content.subtitle = "Teather"
        center.add(UNNotificationRequest(identifier: "teather-test", content: content, trigger: nil)) { error in if let error { DispatchQueue.main.async { self.lastError = error.localizedDescription } } }
    }
    func dismissAll() {
        for key in mirror.items.keys { apply(mirror.dismissLocally(key)); center.removeDeliveredNotifications(withIdentifiers: [Self.notificationID(key)]) }
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) { completionHandler([.banner, .list, .sound]) }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        DispatchQueue.main.async {
            defer { completionHandler() }
            guard response.actionIdentifier == UNNotificationDismissActionIdentifier || response.actionIdentifier == "dismissBoth", let key = response.notification.request.content.userInfo["key"] as? String, let revision = response.notification.request.content.userInfo["revision"] as? String, self.mirror.items[key]?.revision == revision else { return }
            self.apply(self.mirror.dismissLocally(key))
            center.removeDeliveredNotifications(withIdentifiers: [Self.notificationID(key)])
        }
    }
    static func notificationID(_ key: String) -> String { "mirror-" + SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined() }
    static func tailnetAddress() -> String {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return "" }
        defer { freeifaddrs(list) }
        var current: UnsafeMutablePointer<ifaddrs>? = first
        while let node = current {
            defer { current = node.pointee.ifa_next }
            guard let addr = node.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &buffer, socklen_t(buffer.count), nil, 0, NI_NUMERICHOST) == 0 {
                let ip = String(cString: buffer)
                if Tailnet.isIPv4(ip) { return ip }
            }
        }
        return ""
    }
}

struct SettingsView: View {
    @ObservedObject var model: AppModel
    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            HStack(spacing: 14) {
                Image(systemName: "link").font(.system(size: 28, weight: .medium)).frame(width: 60, height: 60).background(Color.green.opacity(0.14), in: RoundedRectangle(cornerRadius: 18))
                VStack(alignment: .leading, spacing: 4) { Text("Teather").font(.largeTitle.bold()); Text("Your phone, a little closer.").foregroundStyle(.secondary) }
                Spacer()
            }
            HStack(spacing: 8) { Circle().fill(model.connected ? Color.green : Color.orange).frame(width: 7, height: 7); Text(model.status); Spacer(); Text("\(model.count) notifications").foregroundStyle(.secondary) }
            GroupBox("Pair your Android phone") {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Connect both devices to Tailscale, then enter these details in the Android app.").foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    HStack { Text("Tailscale IPv4").frame(width: 105, alignment: .leading); Text(model.address.isEmpty ? "Copy from the Tailscale menu" : model.address).font(.system(.body, design: .monospaced)).textSelection(.enabled); Spacer(); Button { model.refreshAddress() } label: { Image(systemName: "arrow.clockwise") }.help("Refresh Tailscale address") }
                    HStack {
                        Text("Pairing key").frame(width: 105, alignment: .leading)
                        Text(model.revealKey ? model.token : "••••••••••••••••••••••••").font(.system(.caption, design: .monospaced)).lineLimit(1).textSelection(.enabled)
                        Spacer()
                        Button(model.revealKey ? "Hide" : "Show") { model.revealKey.toggle() }
                        Button("Copy") { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(model.token, forType: .string) }.disabled(model.token.isEmpty)
                    }
                    Text("The key stays in Keychain. Only one paired phone can connect.").font(.caption).foregroundStyle(.secondary)
                }.padding(8)
            }
            GroupBox("Android folder") {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Label(model.sharedFolder.isEmpty ? "No shared folder" : model.sharedFolder, systemImage: "externaldrive.badge.wifi")
                        Spacer()
                        Button(model.mounting ? "Mounting…" : model.mountedFolder == nil ? "Mount in Finder" : "Open in Finder") { model.mountFolder() }.disabled(model.sharedFolder.isEmpty || !model.connected || model.mounting)
                        if model.mountedFolder != nil { Button("Eject") { model.ejectFolder() } }
                    }
                    Text(model.folderMessage).font(.caption).foregroundStyle(.secondary)
                }.padding(8)
            }
            GroupBox("Notifications & startup") {
                VStack(alignment: .leading, spacing: 12) {
                    HStack { Label(model.notificationsAllowed ? "Notifications allowed" : "Notification permission needed", systemImage: model.notificationsAllowed ? "checkmark.circle" : "bell.badge"); Spacer(); Button("Allow notifications") { model.askPermission() } }
                    Toggle("Launch Teather at login", isOn: Binding(get: { model.launchAtLogin }, set: { model.login($0) }))
                    HStack { Button("Send test notification") { model.testNotification() }; Spacer(); Button(model.isPaused ? "Resume syncing" : "Pause syncing") { model.togglePause() } }
                }.padding(8)
            }
            if !model.lastError.isEmpty { Text(model.lastError).font(.caption).foregroundStyle(.orange).textSelection(.enabled) }
            HStack { Text("0.2.1 · Tailscale port 41691").font(.caption).foregroundStyle(.secondary); Spacer(); Button("Reset pairing…") { model.resetAlert = true } }
        }
        .padding(28).frame(width: 580)
        .onAppear { model.refreshPermission(); model.refreshAddress() }
        .alert("Disconnect your paired phone?", isPresented: $model.resetAlert) { Button("Cancel", role: .cancel) {}; Button("Reset pairing", role: .destructive) { model.resetPairing() } } message: { Text("This replaces the key and clears mirrored notifications. Pair again on your phone.") }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    var model: AppModel!
    var statusItem: NSStatusItem!
    var window: NSWindow!
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        model = AppModel()
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        statusItem.button?.image = NSImage(systemSymbolName: "link", accessibilityDescription: "Teather")
        statusItem.button?.target = self; statusItem.button?.action = #selector(showMenu)
        let content = SettingsView(model: model)
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 580, height: 680), styleMask: [.titled, .closable, .miniaturizable], backing: .buffered, defer: false)
        window.title = "Teather"; window.contentView = NSHostingView(rootView: content); window.isReleasedWhenClosed = false; window.center()
        if !UserDefaults.standard.bool(forKey: "hasOpened") { showSettings(); UserDefaults.standard.set(true, forKey: "hasOpened") }
    }
    @objc func showMenu() {
        let menu = NSMenu()
        let status = NSMenuItem(title: model.status, action: nil, keyEquivalent: ""); status.isEnabled = false; menu.addItem(status)
        menu.addItem(NSMenuItem.separator())
        add(menu, model.mountedFolder == nil ? "Mount Android folder" : "Open Android folder", #selector(mountFolder), "")
        if model.mountedFolder != nil { add(menu, "Eject Android folder", #selector(ejectFolder), "") }
        add(menu, "Settings…", #selector(showSettings), ",")
        add(menu, model.isPaused ? "Resume syncing" : "Pause syncing", #selector(pause), "")
        add(menu, "Dismiss mirrored notifications", #selector(dismissAll), "")
        menu.addItem(NSMenuItem.separator()); add(menu, "Quit Teather", #selector(quit), "q")
        statusItem.menu = menu; statusItem.button?.performClick(nil); statusItem.menu = nil
    }
    private func add(_ menu: NSMenu, _ title: String, _ action: Selector, _ key: String) { let item = NSMenuItem(title: title, action: action, keyEquivalent: key); item.target = self; menu.addItem(item) }
    @objc func showSettings() { NSApp.activate(ignoringOtherApps: true); window.makeKeyAndOrderFront(nil) }
    @objc func mountFolder() { model.mountFolder() }
    @objc func ejectFolder() { model.ejectFolder() }
    @objc func pause() { model.togglePause() }
    @objc func dismissAll() { model.dismissAll() }
    @objc func quit() { NSApp.terminate(nil) }
}

@main
struct TeatherMain {
    static func main() {
        let application = NSApplication.shared
        let delegate = AppDelegate()
        application.delegate = delegate
        withExtendedLifetime(delegate) { application.run() }
    }
}
