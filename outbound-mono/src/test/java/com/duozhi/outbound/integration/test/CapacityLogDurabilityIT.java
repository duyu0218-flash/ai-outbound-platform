package com.duozhi.outbound.integration.test;

import static org.assertj.core.api.Assertions.*;

import java.sql.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

/** Isolated recovery qualification for the optional compressed binlog profile. */
class CapacityLogDurabilityIT {
    @Test
    void compressedBinlogSurvivesCrashAndReplaysCommittedRowsOnly() throws Exception {
        var image =
                new org.testcontainers.images.builder.ImageFromDockerfile(
                                "v5-mysql-binlog-tools:8.4", false)
                        .withDockerfileFromBuilder(
                                builder ->
                                        builder.from("mysql:8.4")
                                                .run(
                                                        "curl -fL --max-time 60"
                                                                + " https://repo.mysql.com/mysql84-community-release-el9-4.noarch.rpm"
                                                                + " -o /tmp/mysql-repo.rpm && rpm -Uvh"
                                                                + " /tmp/mysql-repo.rpm && rm"
                                                                + " /tmp/mysql-repo.rpm")
                                                .run(
                                                        "microdnf --disablerepo='mysql*' install -y"
                                                                + " cpio && microdnf clean all")
                                                .run(
                                                        "rpm --import"
                                                                + " /etc/pki/rpm-gpg/RPM-GPG-KEY-mysql-2025"
                                                                + " && mysql_client_version=$(rpm -q"
                                                                + " --qf"
                                                                + " '%{VERSION}-%{RELEASE}.%{ARCH}'"
                                                                + " mysql-community-server-minimal) &&"
                                                                + " curl -fL --max-time 90"
                                                                + " https://repo.mysql.com/yum/mysql-8.4-community/el/9/$(uname"
                                                                + " -m)/mysql-community-client-${mysql_client_version}.rpm"
                                                                + " -o /tmp/mysql-client.rpm && rpm"
                                                                + " --checksig /tmp/mysql-client.rpm &&"
                                                                + " mkdir /tmp/mysql-client-extract &&"
                                                                + " cd /tmp/mysql-client-extract &&"
                                                                + " rpm2cpio /tmp/mysql-client.rpm |"
                                                                + " cpio -id ./usr/bin/mysqlbinlog &&"
                                                                + " cp usr/bin/mysqlbinlog"
                                                                + " /usr/bin/mysqlbinlog &&"
                                                                + " /usr/bin/mysqlbinlog --version &&"
                                                                + " rm -rf /tmp/mysql-client.rpm"
                                                                + " /tmp/mysql-client-extract")
                                                .build());
        var name =
                org.testcontainers.utility.DockerImageName.parse(
                                image.get(180, java.util.concurrent.TimeUnit.SECONDS))
                        .asCompatibleSubstituteFor("mysql");
        try (var mysql =
                new MySQLContainer(name)
                        .withCommand(
                                "--binlog-transaction-compression=ON",
                                "--innodb-flush-log-at-trx-commit=1",
                                "--sync-binlog=1",
                                "--transaction-isolation=REPEATABLE-READ")) {
            mysql.start();
            String base = mysql.getJdbcUrl();
            String url =
                    base
                            + (base.contains("?") ? "&" : "?")
                            + "connectTimeout=3000&socketTimeout=5000";
            String password = mysql.getPassword();
            String payload = "retained-compressed-transaction-evidence-".repeat(10000);
            String file;
            long begin, end;
            try (var db = DriverManager.getConnection(url, "root", password);
                    var q = db.createStatement()) {
                try (var r =
                        q.executeQuery(
                                "SELECT"
                                        + " @@log_bin,@@binlog_transaction_compression,@@innodb_flush_log_at_trx_commit,@@sync_binlog,@@transaction_isolation")) {
                    r.next();
                    assertThat(r.getInt(1)).isEqualTo(1);
                    assertThat(r.getInt(2)).isEqualTo(1);
                    assertThat(r.getInt(3)).isEqualTo(1);
                    assertThat(r.getInt(4)).isEqualTo(1);
                    assertThat(r.getString(5)).isEqualTo("REPEATABLE-READ");
                }
                q.execute(
                        "CREATE TABLE capacity_log_probe(id INT PRIMARY KEY,payload LONGTEXT NOT"
                                + " NULL) ENGINE=InnoDB");
                q.execute("INSERT INTO capacity_log_probe VALUES(1,'baseline')");
                try (var r = q.executeQuery("SHOW BINARY LOG STATUS")) {
                    assertThat(r.next()).isTrue();
                    file = r.getString(1);
                    begin = r.getLong(2);
                }
                db.setAutoCommit(false);
                try (var p =
                        db.prepareStatement("UPDATE capacity_log_probe SET payload=? WHERE id=1")) {
                    p.setString(1, payload);
                    assertThat(p.executeUpdate()).isEqualTo(1);
                }
                try (var p = db.prepareStatement("INSERT INTO capacity_log_probe VALUES(2,?)")) {
                    p.setString(1, payload);
                    assertThat(p.executeUpdate()).isEqualTo(1);
                }
                db.commit();
                try (var r = q.executeQuery("SHOW BINARY LOG STATUS")) {
                    r.next();
                    assertThat(r.getString(1)).isEqualTo(file);
                    end = r.getLong(2);
                }
            }
            // Only this test's disposable server is killed. Its volume remains.
            boolean killed = false;
            try (var pending = DriverManager.getConnection(url, "root", password);
                    var p = pending.createStatement()) {
                pending.setAutoCommit(false);
                p.executeUpdate("INSERT INTO capacity_log_probe VALUES(3,'uncommitted')");
                mysql.getDockerClient()
                        .killContainerCmd(mysql.getContainerId())
                        .withSignal("KILL")
                        .exec();
                killed = true;
                mysql.getDockerClient().startContainerCmd(mysql.getContainerId()).exec();
            } catch (SQLException disconnected) {
                if (!killed) throw disconnected;
            }
            assertThat(killed).as("the isolated server really received KILL").isTrue();
            // Docker may assign a new ephemeral host port on container restart.
            var inspection =
                    mysql.getDockerClient().inspectContainerCmd(mysql.getContainerId()).exec();
            var binding =
                    inspection
                            .getNetworkSettings()
                            .getPorts()
                            .getBindings()
                            .get(new com.github.dockerjava.api.model.ExposedPort(3306));
            assertThat(binding).isNotEmpty();
            url =
                    "jdbc:mysql://"
                            + mysql.getHost()
                            + ":"
                            + binding[0].getHostPortSpec()
                            + "/"
                            + mysql.getDatabaseName()
                            + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=5000";
            long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            Connection recovered = null;
            while (recovered == null && System.nanoTime() < deadline) {
                try {
                    recovered = DriverManager.getConnection(url, "root", password);
                } catch (SQLException starting) {
                    Thread.sleep(200);
                }
            }
            assertThat(recovered).as("isolated MySQL recovery").isNotNull();
            try (var db = recovered;
                    var q = db.createStatement()) {
                assertRows(db, payload);
                q.executeUpdate("DELETE FROM capacity_log_probe");
                q.executeUpdate("INSERT INTO capacity_log_probe VALUES(1,'baseline')");
            }
            assertThat(file).matches("[A-Za-z0-9_.-]+");
            assertThat(end).isGreaterThan(begin);
            String replay =
                    "set -euo pipefail; MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysqlbinlog --user=root"
                            + " --host=127.0.0.1 --read-from-remote-server --start-position="
                            + begin
                            + " --stop-position="
                            + end
                            + " "
                            + file
                            + " | MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql --user=root";
            var result = mysql.execInContainer("bash", "-c", replay);
            assertThat(result.getExitCode())
                    .as("compressed binlog replay: %s", result.getStderr())
                    .isZero();
            try (var db = DriverManager.getConnection(url, "root", password)) {
                assertRows(db, payload);
            }
        }
    }

    private static void assertRows(Connection db, String expected) throws SQLException {
        try (var q = db.createStatement();
                var rows =
                        q.executeQuery("SELECT id,payload FROM capacity_log_probe ORDER BY id")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(1);
            assertThat(rows.getString(2)).isEqualTo(expected);
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(2);
            assertThat(rows.getString(2)).isEqualTo(expected);
            assertThat(rows.next()).isFalse();
        }
    }
}
