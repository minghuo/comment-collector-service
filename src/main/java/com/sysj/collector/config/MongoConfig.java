package com.sysj.collector.config;


import com.mongodb.ConnectionString;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mapping.model.SnakeCaseFieldNamingStrategy;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

@Configuration
@Log4j2
public class MongoConfig {

    @Value("${mark.uri}")
    private String markUri;

    @Value("${sq.uri}")
    private String sqUri;

    @Value("${auto_task.uri}")
    private String autoTaskUri;

    @Value("${hot_rank.uri}")
    private String hotRankUri;

    private MongoDatabaseFactory mongoDbFactory(String uri) {
        return new SimpleMongoClientDatabaseFactory(new ConnectionString(uri));
    }

    @Primary
    @Bean(name = "markMongoTemplate")
    public MongoTemplate getMarkMongoTemplate() {
        return initMongoTemplate(markUri);
    }

    @Bean(name = "sqMongoTemplate")
    public MongoTemplate getSqMongoTemplate() {
        return initMongoTemplate(sqUri);
    }

    @Bean(name = "autoTaskMongoTemplate")
    public MongoTemplate getAutoTaskMongoTemplate() {
        return initMongoTemplate(autoTaskUri);
    }

    @Bean(name = "hotRankMongoTemplate")
    public MongoTemplate getHotRankMongoTemplate() {
        return initMongoTemplate(hotRankUri);
    }


    private MongoTemplate initMongoTemplate(String uri) {
        MongoDatabaseFactory mongoDbFactory = mongoDbFactory(uri);
        DbRefResolver dbRefResolver = new DefaultDbRefResolver(mongoDbFactory);
        // 设置字段命名策略为下划线式
        MongoMappingContext mongoMappingContext = new MongoMappingContext();
        mongoMappingContext.setFieldNamingStrategy(new SnakeCaseFieldNamingStrategy());
        MappingMongoConverter converter = new MappingMongoConverter(dbRefResolver, mongoMappingContext);
        // 不插入_class
        converter.setTypeMapper(new DefaultMongoTypeMapper(null));
        return new MongoTemplate(mongoDbFactory(uri), converter);
    }

}
