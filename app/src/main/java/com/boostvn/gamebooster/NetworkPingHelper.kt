package com.boostvn.gamebooster

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Đo độ trễ mạng THẬT bằng cách bấm giờ thời gian kết nối socket TCP tới máy chủ DNS
 * công cộng (Google 8.8.8.8, cổng 53). Đây là cách đo ping phổ biến trên Android vì
 * ICMP ping (lệnh ping truyền thống) thường bị mạng di động chặn, còn kết nối TCP thì
 * hầu như luôn được cho phép.
 */
object NetworkPingHelper {
    fun measurePingMs(host: String = "8.8.8.8", port: Int = 53, timeoutMs: Int = 1500): Long {
        return try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            -1L
        }
    }
}
