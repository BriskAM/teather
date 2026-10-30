import Foundation
import NetFS
import Darwin
import TeatherCore

public enum NetworkDisk {
    /// Password is supplied directly to NetFS, never placed in a URL, process argument, or log.
    public static func mount(host: String, secret: String, readOnly: Bool, allowLoopbackForTesting: Bool = false, port: Int = 41692, path: String = "/Teather/") throws -> URL {
        guard Tailnet.isIPv4(host) || (allowLoopbackForTesting && host == "127.0.0.1"), secret.count == 64, (1...65535).contains(port), ["/Teather/", "/Kunnect/"].contains(path) else { throw NSError(domain: "Teather disk", code: Int(EINVAL)) }
        let remote = URL(string: "http://\(host):\(port)\(path)")!
        let open = NSMutableDictionary(dictionary: [kNAUIOptionKey as String: kNAUIOptionNoUI, kNetFSAllowLoopbackKey as String: allowLoopbackForTesting])
        let options = NSMutableDictionary(dictionary: [kNetFSMountFlagsKey as String: readOnly ? MNT_RDONLY : 0, kNetFSAllowSubMountsKey as String: true, kNetFSSoftMountKey as String: true])
        var paths: Unmanaged<CFArray>?
        let status = NetFSMountURLSync(remote as CFURL, nil, "kunnect" as CFString, secret as CFString, open, options, &paths)
        let mounted = paths?.takeRetainedValue() as? [String] ?? []
        guard status == 0, let first = mounted.first else { throw NSError(domain: "Teather disk", code: Int(status), userInfo: [NSLocalizedDescriptionKey: "Couldn’t mount the Android folder (\(status)). Check Tailscale and folder sharing on your phone."]) }
        return URL(fileURLWithPath: first)
    }
}
