import Foundation
import Network
import KunnectCore

final class Peer {
    let id = UUID()
    let connection: NWConnection
    var framer = LineFramer()
    var authenticated = false
    var lastReceived = Date()
    var deviceID = ""
    init(_ connection: NWConnection) { self.connection = connection }
    func send(_ value: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: value) else { return }
        connection.send(content: data + Data([10]), completion: .contentProcessed { [weak self] error in if error != nil { self?.connection.cancel() } })
    }
}

public final class BridgeServer {
    public init() {}
    // All listener/session state is confined to this serial queue.
    private let queue = DispatchQueue(label: "dev.kunnect.connection", qos: .utility)
    private var listener: NWListener?
    private var peers: [UUID: Peer] = [:]
    private var active: UUID?
    private var timer: DispatchSourceTimer?
    private var token = ""
    private var pairedDevice = ""
    public var onMessage: (([String: Any]) -> Void)?
    public var onStatus: ((String) -> Void)?
    public var onPair: ((String) -> Void)?
    public func start(token: String, device: String) {
        queue.async { [self] in
            guard listener == nil else { return }
            self.token = token; self.pairedDevice = device
            do {
                let parameters = NWParameters.tcp
                parameters.allowLocalEndpointReuse = true
                let server = try NWListener(using: parameters, on: 41691)
                listener = server
                server.newConnectionHandler = { [weak self] in self?.accept($0) }
                server.stateUpdateHandler = { [weak self] state in
                    if case .ready = state { self?.status("Waiting for your phone") }
                    if case .failed(let error) = state { self?.status("Listener error: \(error.localizedDescription)"); self?.stopOnQueue() }
                }
                server.start(queue: queue)
                let clock = DispatchSource.makeTimerSource(queue: queue)
                clock.schedule(deadline: .now() + 10, repeating: 10)
                clock.setEventHandler { [weak self] in
                    guard let self else { return }
                    for peer in self.peers.values where Date().timeIntervalSince(peer.lastReceived) > (peer.authenticated ? 75 : 10) { peer.connection.cancel() }
                }
                timer = clock; clock.resume()
            } catch { status("Couldn’t listen on port 41691") }
        }
    }
    public func stop() { queue.async { self.stopOnQueue(); self.status("Paused") } }
    private func stopOnQueue() {
        timer?.cancel(); timer = nil; listener?.cancel(); listener = nil
        for peer in peers.values { peer.connection.cancel() }
        peers.removeAll(); active = nil
    }
    public func send(_ event: [String: Any]) { queue.async { if let id = self.active { self.peers[id]?.send(event) } } }
    private func status(_ text: String) { DispatchQueue.main.async { self.onStatus?(text) } }
    private func accept(_ connection: NWConnection) {
        guard peers.count < 8, case .hostPort(let host, _) = connection.endpoint, Tailnet.acceptsPeer(String(describing: host)) else { connection.cancel(); return }
        let peer = Peer(connection); peers[peer.id] = peer
        connection.stateUpdateHandler = { [weak self, weak peer] state in
            guard let self, let peer else { return }
            if case .ready = state { self.receive(peer) }
            if case .failed = state { peer.connection.cancel() }
            if case .cancelled = state {
                self.peers.removeValue(forKey: peer.id)
                if self.active == peer.id { self.active = nil; self.status("Phone disconnected") }
            }
        }
        connection.start(queue: queue)
    }
    private func receive(_ peer: Peer) {
        peer.connection.receive(minimumIncompleteLength: 1, maximumLength: 32768) { [weak self, weak peer] data, _, complete, error in
            guard let self, let peer else { return }
            if let data {
                do {
                    for line in try peer.framer.append(data) {
                        guard let object = try JSONSerialization.jsonObject(with: line) as? [String: Any] else { throw WireError.invalidFrame }
                        guard self.handle(object, peer: peer) else { peer.connection.cancel(); return }
                        peer.lastReceived = Date()
                    }
                } catch { peer.connection.cancel(); return }
            }
            if complete || error != nil { peer.connection.cancel() } else { self.receive(peer) }
        }
    }
    private func handle(_ event: [String: Any], peer: Peer) -> Bool {
        let type = event["type"] as? String ?? ""
        if !peer.authenticated {
            guard type == "hello", event["version"] as? Int == 1, let candidate = event["token"] as? String, Authentication.matches(candidate, token), let id = event["deviceId"] as? String, UUID(uuidString: id) != nil, pairedDevice.isEmpty || pairedDevice == id else {
                peer.send(["type": "error", "message": "Pairing key or paired phone does not match"]); return false
            }
            if let old = active { peers[old]?.connection.cancel() }
            peer.authenticated = true; peer.deviceID = id; active = peer.id
            if pairedDevice.isEmpty { pairedDevice = id; DispatchQueue.main.async { self.onPair?(id) } }
            peer.send(["type": "ready", "version": 1]); status("Connected · \((event["name"] as? String ?? "Android").prefix(128))")
            return true
        }
        guard active == peer.id else { return false }
        if type == "ping" { peer.send(["type": "pong"]); return true }
        guard ["snapshot", "delta", "dismissResult", "test", "unavailable"].contains(type) else { return false }
        if type == "snapshot" || type == "delta" {
            guard let items = event["items"] as? [[String: Any]], items.count <= 256 else { return false }
        }
        DispatchQueue.main.async { self.onMessage?(event) }
        return true
    }
}
