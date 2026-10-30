import Foundation
import KunnectCore
func XCTAssertEqual<T: Equatable>(_ a: T, _ b: T) { precondition(a == b, "Expected \(b), got \(a)") }
func XCTAssertTrue(_ value: Bool) { precondition(value) }
func XCTAssertFalse(_ value: Bool) { precondition(!value) }
func XCTAssertThrowsError<T>(_ value: @autoclosure () throws -> T) { do { _ = try value(); preconditionFailure("Expected an error") } catch {} }
final class SyncTests {
    func item(_ key: String = "m", _ v: String = "a", dismissible: Bool = true) -> NotificationItem { NotificationItem(key: key, revision: String(repeating: v, count: 64), app: "Messages", package: "test", title: "Hello", body: "World", dismissible: dismissible, sound: false) }
    func testSnapshotIsSilentAndDuplicatesDoNotAlert() {
        var m = NotificationMirror(); let n = item()
        XCTAssertEqual(m.sync([n], full: true), [.present(n, silent: true)])
        XCTAssertEqual(m.sync([n], full: true), [])
        XCTAssertEqual(m.sync([item("m", "b")], full: false), [.present(item("m", "b"), silent: false)])
    }
    func testAndroidRemovalDoesNotEchoDismiss() {
        var m = NotificationMirror(); _ = m.sync([item()], full: true)
        XCTAssertEqual(m.sync([], removed: ["m"], full: false), [.remove("m")]); XCTAssertTrue(m.items.isEmpty)
    }
    func testMacDismissalNeverDismissesNewerRevision() {
        var m = NotificationMirror(); let n = item(); _ = m.sync([n], full: true)
        XCTAssertEqual(m.dismissLocally(n.key), [.dismiss(n.key, revision: n.revision)])
        XCTAssertEqual(m.sync([n], full: true), [.dismiss(n.key, revision: n.revision)])
        XCTAssertEqual(m.sync([item("m", "b")], full: true), [.present(item("m", "b"), silent: true)])
    }
    func testProtectedNotificationIsLocalOnly() {
        var m = NotificationMirror(); let n = item(dismissible: false); _ = m.sync([n], full: true)
        XCTAssertEqual(m.dismissLocally(n.key), []); XCTAssertEqual(m.sync([n], full: true), [])
    }
    func testPendingDismissalExpires() {
        var m = NotificationMirror(); let n = item(); let now = Date(); _ = m.sync([n], full: true); _ = m.dismissLocally(n.key, now: now)
        XCTAssertEqual(m.sync([n], full: true, now: now.addingTimeInterval(601)), [])
    }
    func testResolvedCommandDoesNotRepeat() {
        var m = NotificationMirror(); let n = item(); _ = m.sync([n], full: true); _ = m.dismissLocally(n.key)
        m.resolve(n.key, revision: n.revision, result: "protected"); XCTAssertEqual(m.sync([n], full: true), [])
    }
    func testSnapshotRemovesMissingItems() {
        var m = NotificationMirror(); _ = m.sync([item("a"), item("b")], full: true)
        XCTAssertEqual(m.sync([item("a")], full: true), [.remove("b")])
    }
    func testFramingHandlesPartialAndMultipleMessages() throws {
        var f = LineFramer(); XCTAssertEqual(try f.append(Data("{\"type\":".utf8)), [])
        XCTAssertEqual(try f.append(Data("\"ping\"}\n{}\n".utf8)).map { String(decoding: $0, as: UTF8.self) }, ["{\"type\":\"ping\"}", "{}"])
    }
    func testFramingIsBounded() {
        var f = LineFramer(); XCTAssertThrowsError(try f.append(Data(repeating: 65, count: LineFramer.limit + 1)))
        var e = LineFramer(); XCTAssertThrowsError(try e.append(Data([10])))
    }
    func testTailnetAddressValidation() {
        for ip in ["100.64.0.1", "100.127.255.255"] { XCTAssertTrue(Tailnet.isIPv4(ip)) }
        for ip in ["100.63.255.255", "100.128.0.1", "192.168.1.2", "example.com", "100.064.0.1", "100.64.0.999"] { XCTAssertFalse(Tailnet.isIPv4(ip)) }
        XCTAssertTrue(Tailnet.acceptsPeer("127.0.0.1")); XCTAssertFalse(Tailnet.acceptsPeer("8.8.8.8"))
    }
    func testAuthentication() {
        let key = String(repeating: "a", count: 64)
        XCTAssertTrue(Authentication.matches(key, key)); XCTAssertFalse(Authentication.matches("a", key)); XCTAssertFalse(Authentication.matches(String(repeating: "b", count: 64), key))
    }
}

@main struct Checks {
    static func main() throws {
        let t = SyncTests()
        t.testSnapshotIsSilentAndDuplicatesDoNotAlert()
        t.testAndroidRemovalDoesNotEchoDismiss()
        t.testMacDismissalNeverDismissesNewerRevision()
        t.testProtectedNotificationIsLocalOnly()
        t.testPendingDismissalExpires()
        t.testResolvedCommandDoesNotRepeat()
        t.testSnapshotRemovesMissingItems()
        try t.testFramingHandlesPartialAndMultipleMessages()
        t.testFramingIsBounded()
        t.testTailnetAddressValidation()
        t.testAuthentication()
        print("PASS: 11 notification sync, framing, address, and authentication checks")
    }
}
