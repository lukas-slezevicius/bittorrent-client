package com.slezevicius.bittorrent_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;

import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.TestInstance.Lifecycle;

@TestInstance(Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
public class PeerTestSocket extends PeerTest {
    final int debuggerPort = 60001;
    final int peerPort = 60000;
    final byte[] pstrlen = {19};
    final byte[] pstr = {66, 105, 116, 84, 111, 114, 114, 101, 110, 116, 32, 112, 114, 111, 116, 111, 99, 111, 108};
    final byte[] reserved = {0, 0, 0, 0, 0, 0, 0, 0};
    final byte[] infoHash = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19};
    final byte[] peerId = {45, 88, 88, 48, 49, 48, 48, 45, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48, 48};
    final byte[] bitfield = {123, 12, 1, 2, 3, 123, 92, 99, 88, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255, 12};
    InetAddress ip;
    Process dummyPeerProc;
    ServerSocket debuggerServer;
    Socket debugger;
    DataInputStream debuggerIn;
    DataOutputStream debuggerOut;
    Logger log;
    Peer peer;
    TestingPeerManager peerManager;

    @BeforeAll
    void initiAll() {
        try {
            //Set up the logger
            log = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
            String configPath = System.getProperty("user.dir") + "/log.properties";
            FileInputStream configFile = new FileInputStream(configPath);
            LogManager.getLogManager().readConfiguration(configFile);

            //Set up the debugger
            ip = InetAddress.getByName("localhost");
            debuggerServer = new ServerSocket(debuggerPort, 1);
            String testPath = "./src/test/java/com/slezevicius/bittorrent_client/";
            String dummyPeerCMD = "python3 " + testPath + "MockPeer.py " + debuggerPort + " " + peerPort;
            dummyPeerProc = Runtime.getRuntime().exec(dummyPeerCMD);
            debugger = debuggerServer.accept();
            debuggerIn = new DataInputStream(debugger.getInputStream());
            debuggerOut = new DataOutputStream(debugger.getOutputStream());
            peerManager = new TestingPeerManager();
            peerManager.setPeerId(new String(peerId, "US-ASCII"));
            peerManager.setInfoHash(infoHash);
            peerManager.setBitfield(bitfield);
        } catch (UnknownHostException e) {
            fail(e.getMessage());
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }
    @AfterAll
    void destrAll() {
        try {
            peer.shutdownSockets();
            String killCMD = "kill -s SIGINT " + dummyPeerProc.pid();
            Process killProc = Runtime.getRuntime().exec(killCMD);
            try {
                killProc.waitFor(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }
            debuggerServer.close();
            debugger.close();
            debuggerIn.close();
            dummyPeerProc.destroy();
        } catch (IOException e) {
            log.warning("Could not close the echoReceiver");
        }
    }

    @Test
    @Order(1)
    void testConstructor() {
        try {
            byte[] handshakeMessage = ArrayUtils.addAll(
                pstrlen, ArrayUtils.addAll(pstr, ArrayUtils.addAll(
                    reserved, ArrayUtils.addAll(
                        infoHash, peerId))));
            Socket sock = new Socket(ip, peerPort);
            debuggerOut.write(handshakeMessage);
            peer = new Peer(sock);
            peer.introducePeerManager(peerManager);
            assertTrue(Arrays.equals(peer.getInfoHash(), infoHash));
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Field foundByPeerServerField = cls.getField("foundByPeerServer");
            foundByPeerServerField.setAccessible(true);
            boolean foundByPeerServer = (boolean) foundByPeerServerField.get(peer);
            assertTrue(foundByPeerServer);
            Field peerBitfieldField = cls.getField("peerBitfield");
            peerBitfieldField.setAccessible(true);
            byte[] peerBitfield = (byte[]) peerBitfieldField.get(peer);
            assertEquals(peerManager.getBitfieldLength(), peerBitfield.length);
        } catch (IOException | DataFormatException | InterruptedException e) {
            e.printStackTrace();
            fail("Error was thrown; check the stack trace.");
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            fail(e.getCause());
        }
    }

    @Test
    @Order(2)
    void testSendHandshake() {
        //Tests sending handshake straight after the socket constructor
        try {
            byte[] handshakeMessage = ArrayUtils.addAll(
                pstrlen, ArrayUtils.addAll(pstr, ArrayUtils.addAll(
                    reserved, ArrayUtils.addAll(
                        infoHash, peerId))));
            Class cls = Class.forName("com.slezevicius.bittorrent_client.Peer");
            Method method = cls.getDeclaredMethod("sendHandshake");
            method.setAccessible(true);
            method.invoke(peer);
            byte[] resp = new byte[handshakeMessage.length];
            debuggerIn.read(resp);
            assertTrue(debuggerIn.available() == 0);
            assertTrue(Arrays.equals(handshakeMessage, resp));
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            e.printStackTrace();
            fail(e.getMessage());
        } catch (InvocationTargetException e) {
            e.getCause().printStackTrace();
            fail(e.getCause().getMessage());
        }
    }
}