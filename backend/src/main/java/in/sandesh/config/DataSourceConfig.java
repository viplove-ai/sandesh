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
 * Two connections, on purpose.
 *
 * <p>{@code sandesh} is this service's own database — the outbox, push subscriptions, settings.
 * JPA and Flyway own it. {@code nirman} is reached through a role that may only {@code SELECT}
 * from two contract views, and never through JPA: there are no entities for Nirman's tables here
 * and there must not be, because the views are the contract and the tables behind them are not.</p>
 *
 * <p>A single shared database would have saved one pool and cost the boundary: shared vacuum,
 * shared backups, shared restore, and a chat table able to fill Nirman's disk.</p>
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("app.datasource.sandesh")
    public DataSource sandeshDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties("app.datasource.nirman")
    public DataSource nirmanDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    /** The only way this service reads anything of Nirman's. */
    @Bean
    public JdbcTemplate nirmanJdbc(DataSource nirmanDataSource) {
        return new JdbcTemplate(nirmanDataSource);
    }
}
