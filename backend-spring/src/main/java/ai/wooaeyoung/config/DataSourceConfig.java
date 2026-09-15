package ai.wooaeyoung.config;

import org.sqlite.SQLiteDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.File;

/** wooaeyoung.db-path 설정으로 SQLite 파일을 연다(부모 디렉터리 자동 생성). */
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(@Value("${wooaeyoung.db-path}") String dbPath) {
        File file = new File(dbPath);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + file.getPath());
        return ds;
    }
}
