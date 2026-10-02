package org.yurlib.server.security;

import java.util.Arrays;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.yurlib.server.YurlibServerApplication;

public final class OwnerRecoveryCommand {

    private OwnerRecoveryCommand() {}

    public static void main(String[] args) {
        try (var context = new SpringApplicationBuilder(YurlibServerApplication.class)
                .web(WebApplicationType.NONE)
                .run(recoveryArguments(args))) {
            context.getId();
        }
    }

    static String[] recoveryArguments(String[] args) {
        var recoveryArguments = Arrays.copyOf(args, args.length + 2);
        recoveryArguments[args.length] = "--yurlib.security.owner.recovery-mode=true";
        recoveryArguments[args.length + 1] = "--yurlib.library.scan.worker-enabled=false";
        return recoveryArguments;
    }
}
