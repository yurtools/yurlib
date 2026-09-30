package org.yurlib.server.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("yurlib.security.owner")
public record OwnerAccessProperties(String username, String password) {}
