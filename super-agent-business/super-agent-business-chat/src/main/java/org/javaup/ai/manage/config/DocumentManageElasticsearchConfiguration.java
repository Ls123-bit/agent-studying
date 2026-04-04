package org.javaup.ai.manage.config;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文档管理模块 Elasticsearch 配置。
 *
 * <p>这里显式创建 ES 客户端，
 * 而不是依赖 spring.elasticsearch.* 自动配置，
 * 目的是让索引名、账号密码和地址都继续收口在 app.manage.elasticsearch 下管理。</p>
 */
@Configuration
@EnableConfigurationProperties(DocumentManageProperties.class)
@ConditionalOnProperty(prefix = "app.manage.elasticsearch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocumentManageElasticsearchConfiguration {

    /**
     * Elasticsearch 低层 RestClient。
     */
    @Bean(name = "documentManageElasticsearchRestClient", destroyMethod = "close")
    public RestClient documentManageElasticsearchRestClient(DocumentManageProperties properties) {
        DocumentManageProperties.Elasticsearch elasticsearch = properties.getElasticsearch();
        if (CollUtil.isEmpty(elasticsearch.getUris())) {
            throw new IllegalStateException("app.manage.elasticsearch.uris 不能为空");
        }

        HttpHost[] hosts = elasticsearch.getUris().stream()
            .filter(StrUtil::isNotBlank)
            .map(HttpHost::create)
            .toArray(HttpHost[]::new);

        org.elasticsearch.client.RestClientBuilder builder = RestClient.builder(hosts)
            .setRequestConfigCallback(requestConfigBuilder -> requestConfigBuilder
                .setConnectTimeout(elasticsearch.getConnectTimeoutMillis())
                .setSocketTimeout(elasticsearch.getSocketTimeoutMillis()));

        if (StrUtil.isNotBlank(elasticsearch.getUsername())) {
            CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                AuthScope.ANY,
                new UsernamePasswordCredentials(
                    elasticsearch.getUsername(),
                    StrUtil.blankToDefault(elasticsearch.getPassword(), "")
                )
            );
            builder.setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder
                .setDefaultCredentialsProvider(credentialsProvider));
        }
        return builder.build();
    }

    /**
     * Elasticsearch 传输层。
     */
    @Bean(name = "documentManageElasticsearchTransport", destroyMethod = "close")
    public ElasticsearchTransport documentManageElasticsearchTransport(
        @Qualifier("documentManageElasticsearchRestClient") RestClient restClient,
        com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper));
    }

    /**
     * 文档管理模块专用 ElasticsearchClient。
     */
    @Bean(name = "documentManageElasticsearchClient")
    public ElasticsearchClient documentManageElasticsearchClient(
        @Qualifier("documentManageElasticsearchTransport") ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
