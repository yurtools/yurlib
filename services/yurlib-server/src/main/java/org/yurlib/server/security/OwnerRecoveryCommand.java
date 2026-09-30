package org.yurlib.server.security;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.yurlib.server.YurlibServerApplication;

public final class OwnerRecoveryCommand {

    private OwnerRecoveryCommand() {}

    public static void main(String[] args) {
        try (var context = new SpringApplicationBuilder(YurlibServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties("yurlib.security.owner.recovery-mode=true", "yurlib.library.scan.worker-enabled=false")
                .run(args)) {
            context.getId();
        }
    }
}
