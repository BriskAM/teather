import Foundation
import Darwin
import KunnectTransport

final class Client {
    let fd: Int32
    init() {
        fd = socket(AF_INET, SOCK_STREAM, 0)
        var timeout = timeval(tv_sec: 5, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        var noSignal: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &noSignal, socklen_t(MemoryLayout<Int32>.size))
        var address = sockaddr_in(); address.sin_family = sa_family_t(AF_INET); address.sin_port = UInt16(41691).bigEndian; inet_pton(AF_INET, "127.0.0.1", &address.sin_addr)
        let result = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
        precondition(result == 0, "Cannot connect to test listener")
    }
    deinit { Darwin.close(fd) }
    func send(_ event: [String: Any]) {
        let data = try! JSONSerialization.data(withJSONObject: event) + Data([10])
        data.withUnsafeBytes { bytes in
            var sent = 0
            while sent < bytes.count { let n = Darwin.send(fd, bytes.baseAddress!.advanced(by: sent), bytes.count - sent, 0); precondition(n > 0); sent += n }
        }
    }
    func read() -> [String: Any]? {
        var bytes = Data(); var byte: UInt8 = 0
        while recv(fd, &byte, 1, 0) == 1 {
            if byte == 10 { return (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any] }
            bytes.append(byte)
        }
        return nil
    }
}

@main struct WireChecks {
    static func main() {
        let server = BridgeServer()
        let token = String(repeating: "c", count: 64)
        let id = UUID().uuidString
        let received = DispatchSemaphore(value: 0)
        server.onMessage = { event in precondition(event["type"] as? String == "snapshot"); received.signal() }
        var started = false
        server.onStatus = { status in
            guard status == "Waiting for your phone", !started else { return }; started = true
            DispatchQueue.global().async {
                let bad = Client(); bad.send(["type": "snapshot", "items": []]); precondition(bad.read() == nil)
                let wrong = Client(); wrong.send(["type": "hello", "version": 1, "token": String(repeating: "x", count: 64), "deviceId": id]); precondition(wrong.read()?["type"] as? String != "ready")
                let client = Client(); client.send(["type": "hello", "version": 1, "token": token, "deviceId": id, "name": "Test phone"])
                precondition(client.read()?["type"] as? String == "ready", "Authentication failed")
                client.send(["type": "ping"]); precondition(client.read()?["type"] as? String == "pong")
                client.send(["type": "snapshot", "items": []]); precondition(received.wait(timeout: .now() + 5) == .success)
                server.send(["type": "dismiss", "key": "test", "revision": "rev"])
                let dismiss = client.read(); precondition(dismiss?["type"] as? String == "dismiss" && dismiss?["key"] as? String == "test")
                let other = Client(); other.send(["type": "hello", "version": 1, "token": token, "deviceId": UUID().uuidString]); precondition(other.read()?["type"] as? String != "ready")
                let reconnect = Client(); reconnect.send(["type": "hello", "version": 1, "token": token, "deviceId": id]); precondition(reconnect.read()?["type"] as? String == "ready")
                print("PASS: live TCP authentication, unauthorized rejection, ping, snapshot, dismissal, phone binding, and reconnect")
                server.stop(); Darwin.exit(0)
            }
        }
        server.start(token: token, device: "")
        DispatchQueue.global().asyncAfter(deadline: .now() + 25) { fatalError("Wire test timed out") }
        dispatchMain()
    }
}
