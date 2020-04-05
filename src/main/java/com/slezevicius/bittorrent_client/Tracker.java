package com.slezevicius.bittorrent_client;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

public class Tracker extends Thread {
    private Metainfo metainfo;
    private Torrent torrent;
    private long interval;
    private String trackerId;
    private long complete;
    private long incomplete;
    private Set<Pair<InetAddress, Integer>> peers;
    private volatile boolean keepRunning = true;
    private volatile boolean completed = false;
    private Logger log;


    Tracker(Metainfo metainfo, Torrent torrent) throws URISyntaxException, DataFormatException {
        log  = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        this.metainfo = metainfo;
        this.torrent = torrent;
    }

    @Override
    public void run() {
        try {
            send("started");
            Thread.sleep(interval * 1000);
            while (true) {
                send("");
                Thread.sleep(interval * 1000);
            }
        } catch (IOException e) {
            log.log(Level.WARNING, e.getMessage(), e);
        } catch (URISyntaxException e) {
            log.log(Level.WARNING, e.getMessage(), e);
        } catch (DataFormatException e) {
            log.log(Level.WARNING, e.getMessage(), e);
        } catch (InterruptedException e) {
            if (keepRunning) {
                log.warning(String.format("%s: The thread was interrupted", this.toString()));
            } else {
                try {
                    if (completed) {
                        send("completed"); //Make sure that this receives a response
                    } else {
                        send("stopped");
                    }
                } catch (Exception e2) {
                    log.warning(String.format("%s: Could not send the final message to the tracker", this.toString()));
                    log.log(Level.WARNING, e2.getMessage(), e2);
                }
            }
        }
    }

    private void send(String event) throws URISyntaxException, DataFormatException, IOException {
        String trackerURI = buildTrackerURL(event);
        byte[] responseContent = sendRequest(trackerURI);
        Bencoding b = new Bencoding(responseContent);
        Object responseObject = b.decode();
        updateFields(responseObject);
    }

    private void updateFields(Object responseObject) throws DataFormatException {
        if (responseObject instanceof LinkedHashMap) {
            LinkedHashMap<String, Object> responseDict = (LinkedHashMap<String, Object>) responseObject;
            if (responseDict.containsKey("failure reason")) {
                if (responseDict.get("failure reason") instanceof byte[]) {
                    String failure = new String((byte[]) responseDict.get("failure reason"));
                    throw new DataFormatException(String.format("%s failure: %s", this.toString(), failure));
                } else {
                    throw new DataFormatException(String.format("%s failure reason type is not byte string", this.toString()));
                }
            }
            if (responseDict.containsKey("warning message")) {
                if (responseDict.get("warning message") instanceof byte[]) {
                    String warning = new String((byte[]) responseDict.get("warning message"));
                    throw new DataFormatException(String.format("%s warning: %s", this.toString(), warning));
                } else {
                    log.warning(String.format("%s: warning message type is not byte string"));
                }
            }
            if (responseDict.containsKey("interval")) {
                if (responseDict.get("interval") instanceof Long) {
                    interval = (long) responseDict.get("interval");
                } else {
                    throw new DataFormatException(String.format("%s interval value is not of type int", this.toString()));
                }
            } else {
                throw new DataFormatException(String.format("%s interval key not in the dict", this.toString()));
            }
            if (responseDict.containsKey("complete")) {
                if (responseDict.get("complete") instanceof Long) {
                    complete = (long) responseDict.get("complete");
                } else {
                    throw new DataFormatException(String.format("%s complete value is not of type int", this.toString()));
                }
            } else {
                throw new DataFormatException(String.format("%s complete key not in the dict", this.toString()));
            }
            if (responseDict.containsKey("incomplete")) {
                if (responseDict.get("incomplete") instanceof Long) {
                    incomplete = (long) responseDict.get("incomplete");
                } else {
                    throw new DataFormatException(String.format("%s incomplete value is not of type int", this.toString()));
                }
            } else {
                throw new DataFormatException(String.format("%s incomplete key not in the dict", this.toString()));
            }
            if (responseDict.containsKey("peers")) {
                if (responseDict.get("peers") instanceof ArrayList) {
                    //List model of peers
                    updatePeers((ArrayList<Object>) responseDict.get("peers"));
                } else if (responseDict.get("peers") instanceof byte[]) {
                    //Compact model of peers
                    updatePeers((byte[]) responseDict.get("peers"));
                } else {
                    throw new DataFormatException(String.format("%s peers is of an invalid type", this.toString()));
                }
            } else {
                throw new DataFormatException(String.format("%s peers key not in the dict", this.toString()));
            }
        } else {
            throw new DataFormatException(String.format("%s the response from the tracker must be a dictionary.", this.toString()));
        }
    }

    private void updatePeers(byte[] peerBytes) throws DataFormatException {
        if (peerBytes.length % 6 != 0) {
            throw new DataFormatException(String.format("%s invalid length of peer byte array", this.toString()));
        }
        peers = new HashSet<Pair<InetAddress, Integer>>(50);
        int i = 0;
        while (i < peerBytes.length) {
            InetAddress ip;
            try {
                ip = InetAddress.getByAddress(Arrays.copyOfRange(peerBytes, i, i + 4));
            } catch (UnknownHostException e) {
                log.fine(String.format("%s unknown host", this.toString()));
                continue;
            }
            int port = (peerBytes[i + 4] & 0xFF)*256 + (peerBytes[i + 4 +1] & 0xFF);
            peers.add(new Pair<InetAddress, Integer>(ip, port));
            i += 6;
        }
    }

    private void updatePeers(ArrayList<Object> peerList) throws DataFormatException {
        peers = new HashSet<Pair<InetAddress, Integer>>(50);
        for (Object obj : peerList) {
            if (obj instanceof HashMap) {
                HashMap<String, Object> dict = (HashMap<String, Object>) obj;
                InetAddress ip;
                int port;
                if (!dict.containsKey("peer id")) {
                    throw new DataFormatException(String.format("%s the dict in the peer list has to contain the key peer id", this.toString()));
                }
                if (dict.containsKey("ip")) {
                    if (dict.get("ip") instanceof byte[]) {
                        try {
                            ip = InetAddress.getByName(new String((byte[]) dict.get("ip")));
                        } catch (UnknownHostException e) {
                            log.fine(String.format("%s unknown host", this.toString()));
                            continue;
                        }
                    } else {
                        throw new DataFormatException(String.format("%s the provided ip address in peer list is not of type byte string", this.toString()));
                    }
                } else {
                    throw new DataFormatException(String.format("%s the dict in the peer list has to contain the key ip", this.toString()));
                }
                if (dict.containsKey("port")) {
                    if (dict.get("port") instanceof Long) {
                        port = (int) dict.get("port");
                    } else {
                        throw new DataFormatException(String.format("%s the provided port in peer list is not of type int", this.toString()));
                    }
                } else {
                    throw new DataFormatException(String.format("%s the dict in the peer list has to contain the key port", this.toString()));
                }
                peers.add(new Pair<InetAddress, Integer>(ip, port));
            } else {
                throw new DataFormatException(String.format("%s an element of the peer list is not of type dict", this.toString()));
            }
        }
    }

    private String buildTrackerURL(String event) {
        StringBuilder url = new StringBuilder(metainfo.getAnnounce());
        url.append("?info_hash=");
        url.append(percentEncode(metainfo.getInfoHash()));
        url.append("&peer_id=");
        url.append(torrent.getPeerId());
        url.append("&port=");
        url.append(String.valueOf(torrent.getPort()));
        url.append("&uploaded=");
        url.append(String.valueOf(torrent.getUploaded()));
        url.append("&downloaded=");
        url.append(String.valueOf(torrent.getDownloaded()));
        url.append("&left=");
        url.append(String.valueOf(metainfo.getLength() - torrent.getDownloaded()));
        url.append("&compact=1");
        url.append("&event=");
        url.append(event);
        if (trackerId != null) {
            url.append("&trackerid=");
            url.append(trackerId);
        }
        return new String(url);
    }

    private static String percentEncode(byte[] in) {
        StringBuilder out = new StringBuilder(in.length * 2);
        for (int i = 0; i < in.length; i++) {
            if (isLetterOrDigit(in[i]) || isSpecialChar(in[i])) {
                out.append((char) in[i]);
            } else {
                out.append('%');
                out.append(Metainfo.bytesToHex(Arrays.copyOfRange(in, i, i + 1)));
            }
        }
        return new String(out);
    }

    private static boolean isLetterOrDigit(byte c) {
        if ((c >= 97 && c <= 122) || (c >= 65 && c <= 90) || (c >= 48 && c <= 57)) {
            return true;
        }
        return false;
    }

    private static boolean isSpecialChar(byte c) {
        if (c == '_' || c == '-' || c == '.' || c == '~') {
            return true;
        }
        return false;
    }

    private static byte[] sendRequest(String url) throws IOException {
        CloseableHttpClient httpclient = HttpClients.createDefault();
        HttpGet httpGet = new HttpGet(url);
        CloseableHttpResponse response = httpclient.execute(httpGet);
        HttpEntity entity = response.getEntity();
        InputStream stream = entity.getContent();
        byte[] content = stream.readAllBytes();
        stream.close();
        EntityUtils.consume(entity);
        return content;
    }

    public long getInterval() {
        return interval;
    }

    public long getComplete() {
        return complete;
    }

    public long getIncomplete() {
        return incomplete;
    }

    public Set<Pair<InetAddress, Integer>> getPeers() {
        return peers;
    }

    public void stopRunning() {
        keepRunning = false;
        this.interrupt();
    }

    @Override
    public String toString() {
        return String.format("Tracker [name=%s, url=%s]", metainfo.getName(), metainfo.getAnnounce());
    }
}