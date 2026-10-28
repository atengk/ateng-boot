package io.github.atengk.boot.http.registrar.errorfixture;

import io.github.atengk.boot.http.annotation.HttpClient;

/**
 * 空 clientName 测试接口（置于独立包以防被正常扫描用例扫入）
 *
 * @author Ateng
 * @since 2026-10-21
 */
@HttpClient(name = "", url = "http://localhost:8080")
public interface BlankNameClient {
    void test();
}
