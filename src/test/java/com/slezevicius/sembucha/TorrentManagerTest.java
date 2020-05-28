package com.slezevicius.sembucha;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import com.slezevicius.sembucha.TorrentManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TorrentManagerTest {
    Logger log;
    TorrentManager torrentManager;

    @BeforeAll
    void initiAll() {
        log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        try {
            String configPath = System.getProperty("user.dir") + "/log.properies";
            FileInputStream configFile = new FileInputStream(configPath);
            LogManager.getLogManager().readConfiguration(configFile);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage());
        }

    }

    @BeforeEach
    void init() {
        log.info("Starting test");
    }
}