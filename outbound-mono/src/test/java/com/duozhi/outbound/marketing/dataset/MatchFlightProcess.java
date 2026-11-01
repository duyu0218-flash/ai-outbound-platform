package com.duozhi.outbound.marketing.dataset;

import com.duozhi.outbound.marketing.common.MarketingStore;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Child JVM probe: only claims a local database row; cannot call any supplier. */
public class MatchFlightProcess {
    public static void main(String[] args) {
        var env = System.getenv();
        var ds =
                new DriverManagerDataSource(
                        env.get("MATCH_TEST_JDBC"),
                        env.get("MATCH_TEST_USER"),
                        env.get("MATCH_TEST_PASSWORD"));
        var db = new MarketingStore(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var job = db.one("SELECT * FROM mk_match_job WHERE id=?", env.get("MATCH_TEST_JOB"));
        var request =
                db.one("SELECT * FROM mk_match_request WHERE job_id=?", env.get("MATCH_TEST_JOB"));
        System.out.println(new MatchFlights(db, tx).claim(job, request) ? "SEND" : "SHARED");
    }
}
