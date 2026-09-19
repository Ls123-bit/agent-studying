package org.javaup.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.concurrent.TimeUnit;

import static org.javaup.config.DelayQueueProperties.PREFIX;

@Data
@ConfigurationProperties(prefix = PREFIX)
public class DelayQueueProperties {

    public static final String PREFIX = "delay.queue";

    private Integer corePoolSize = 4;

    private Integer maximumPoolSize = 4;

    private long keepAliveTime = 30;

    private TimeUnit unit = TimeUnit.SECONDS;

    private Integer workQueueSize = 256;

    private Integer isolationRegionCount = 5;
}
