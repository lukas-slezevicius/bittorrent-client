package com.slezevicius.bittorrent_client;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.zip.DataFormatException;

public class App 
{
    public static void main( String[] args )
    {
        /*
        String input = "d2:abi12534e4:hahai-12e3:MYRli1ei2ed2:jji59eeee";
        Bencoding b = new Bencoding(input);
        Object out = b.decode();
        System.out.println(out);
        */
        /*
        byte[] cont = Tracker.sendRequest("https://api.covid19api.com/country/spain/status/confirmed");
        try {
            System.out.println(new String(cont, "US-ASCII"));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        */
        Torrent tor;
        try {
            String path = "/home/lukas/Programming/Projects/bittorrent-client/";
            String file = "ubuntu-19.10-desktop-amd64.iso.torrent";
            tor = new Torrent(path + file, "-XX0100-000000000000", 6881);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        } catch (DataFormatException e) {
            e.printStackTrace();
            return;
        } catch (URISyntaxException e) {
            e.printStackTrace();
            return;
        }
        //System.out.println(tor);
    }
}