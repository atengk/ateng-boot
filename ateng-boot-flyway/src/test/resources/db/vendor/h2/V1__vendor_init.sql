CREATE TABLE vendor_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_name VARCHAR(64) NOT NULL
);

INSERT INTO vendor_audit (role_name) VALUES ('${customRole}');
