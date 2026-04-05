package ru.batoyan.vkr.notification.mail.grpc;

import java.time.Duration;

class NotificationHistoryClientProperties {

    private String host = "localhost";
    private int port = 9091;
    private Duration deadline = Duration.ofSeconds(3);

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public Duration getDeadline() {
        return deadline;
    }

    public void setDeadline(Duration deadline) {
        this.deadline = deadline;
    }
}
