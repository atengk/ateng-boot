package io.github.atengk.boot.admin.admission;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 主机名 DNS 地址解析函数式接缝
 * <p>
 * 默认绑定 {@link InetAddress#getAllByName(String)}，测试场景可注入确定性离线桩防止依赖外网或重绑定环境。
 *
 * @author Ateng
 * @since 2026-11-21
 */
@FunctionalInterface
public interface HostAddressResolver {

    /**
     * 默认基于 JDK 系统 DNS 解析器的实现
     */
    HostAddressResolver SYSTEM = InetAddress::getAllByName;

    /**
     * 将目标主机名解析为全部关联网络地址
     *
     * @param host 目标主机名
     * @return 解析得到的地址数组
     * @throws UnknownHostException 无法解析主机名时抛出
     */
    InetAddress[] resolve(String host) throws UnknownHostException;
}
