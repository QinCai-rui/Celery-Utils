package xyz.qincai.celeryutils.logging;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Rewrites verbose shaded dependency logger namespaces to compact names.
 */
public final class NamespaceLogCleaner {

    private static final Map<String, String> PREFIX_REPLACEMENTS = new LinkedHashMap<>();

    static {
        PREFIX_REPLACEMENTS.put("xyz.qincai.celeryutils.shaded.jda.", "JDA");
        PREFIX_REPLACEMENTS.put("net.dv8tion.jda.", "JDA");
        PREFIX_REPLACEMENTS.put("xyz.qincai.celeryutils.shaded.hikari.", "HikariCP");
        PREFIX_REPLACEMENTS.put("com.zaxxer.hikari.", "HikariCP");
    }

    private final Map<Handler, Filter> originalFiltersByHandler = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<String, String> rewrittenLoggerNames = new ConcurrentHashMap<>();

    public void install() {
        Logger rootLogger = Logger.getLogger("");
        for (Handler handler : rootLogger.getHandlers()) {
            Filter originalFilter = handler.getFilter();
            originalFiltersByHandler.put(handler, originalFilter);
            handler.setFilter(new RewritingFilter(originalFilter, rewrittenLoggerNames));
        }
    }

    public void uninstall() {
        Logger rootLogger = Logger.getLogger("");
        for (Handler handler : rootLogger.getHandlers()) {
            if (originalFiltersByHandler.containsKey(handler)) {
                handler.setFilter(originalFiltersByHandler.get(handler));
            }
        }
        originalFiltersByHandler.clear();
        rewrittenLoggerNames.clear();
    }

    private static final class RewritingFilter implements Filter {
        private final Filter delegate;
        private final Map<String, String> rewrittenLoggerNames;

        private RewritingFilter(Filter delegate, Map<String, String> rewrittenLoggerNames) {
            this.delegate = delegate;
            this.rewrittenLoggerNames = rewrittenLoggerNames;
        }

        @Override
        public boolean isLoggable(LogRecord record) {
            rewriteLoggerNameIfNeeded(record);
            return delegate == null || delegate.isLoggable(record);
        }

        private void rewriteLoggerNameIfNeeded(LogRecord record) {
            String loggerName = record.getLoggerName();
            if (loggerName == null || loggerName.isEmpty()) {
                return;
            }

            String rewrittenName = rewrittenLoggerNames.computeIfAbsent(loggerName, RewritingFilter::rewriteLoggerName);
            if (!loggerName.equals(rewrittenName)) {
                record.setLoggerName(rewrittenName);
            }
        }

        private static String rewriteLoggerName(String loggerName) {
            for (Map.Entry<String, String> replacement : PREFIX_REPLACEMENTS.entrySet()) {
                if (loggerName.startsWith(replacement.getKey())) {
                    String suffix = loggerName.substring(replacement.getKey().length());
                    return suffix.isEmpty() ? replacement.getValue() : replacement.getValue() + "." + suffix;
                }
            }
            return loggerName;
        }
    }
}