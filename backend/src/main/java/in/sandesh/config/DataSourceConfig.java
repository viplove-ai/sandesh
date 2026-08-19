package in.sandesh.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * One database, two schemas.
 *
 * <p>Sandesh's own tables live in {@code sandesh}; Nirman's contract views live in
 * {@code public} on the same connection. An earlier draft gave this service its own database and
 * a second read-only role, which bought a cleaner boundary and cost four credentials to keep in
 * step across two systems — and every one of them was a way for the service to fail at boot with
 * an error naming the wrong thing.</p>
 *
 * <p>What is given up is real and worth naming: shared vacuum, shared backups, shared restore,
 * and a chat table that can now fill the disk Nirman is using. What replaces the role boundary is
 * a narrower one — this service reads Nirman's data only through two views, by convention rather
 * than by grant, and {@code NirmanDirectoryService} is still the only class that may.</p>
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("app.datasource")
    public DataSource dataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    /**
     * How the directory reads the contract views. Named for what it is for rather than for what
     * it connects to, so the one class allowed to touch Nirman's data still says so at the seam.
     */
    @Bean
    public JdbcTemplate nirmanJdbc(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
