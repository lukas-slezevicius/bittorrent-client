package com.slezevicius.sembucha;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

public class Bencoding {
    private int idx;
    private byte[] input;

    Bencoding(byte[] input) {
        this.input = input;
    }

    Bencoding(String input) {
        this.input = input.getBytes(StandardCharsets.UTF_8);
    }

    public void setInput(String input) {
        this.input = input.getBytes(StandardCharsets.UTF_8);
        this.idx = 0;
    }

    public void setInput(byte[] input) {
        this.input = input;
        this.idx = 0;
    }

    public Object decode() throws DataFormatException {
        if (input[idx] == 'i') {
            //Bencoded integers
            idx += 1;
            int a = idx;
            while (input[idx] != 'e') {
                idx += 1;
            }
            int b = idx;
            idx += 1;
            return Long.parseLong(new String(input, a, b - a, StandardCharsets.UTF_8));
        } else if (Character.isDigit(input[idx])) {
            //Bencoded byte strings
            int a = idx;
            while (input[idx] != ':') {
                idx += 1;
            }
            int b = idx;
            idx += 1;
            int length = Integer.parseInt(new String(input, a, b - a, StandardCharsets.UTF_8));
            byte[] str = Arrays.copyOfRange(input, idx, idx + length);
            idx += length;
            return str;
        } else if (input[idx] == 'l') {
            //Bencoded list
            ArrayList<Object> list = new ArrayList<Object>();
            idx += 1;
            while (input[idx] != 'e') {
                list.add(decode());
            }
            idx += 1;
            return list;
        } else if (input[idx] == 'd') {
            //Bencoded dictionary
            LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
            idx += 1;
            while (input[idx] != 'e') {
                Object key = decode();
                Object value = decode();
                map.put(new String((byte[]) key), value);
            }
            return map;
        } else {
            throw new DataFormatException("The bencoded string is not formatted properly");
        }
    }

    public static ArrayList<Byte> encode(Object obj) throws DataFormatException {
        ArrayList<Byte> out = new ArrayList<Byte>();
        if (obj instanceof LinkedHashMap) {
            //Dictionary
            out.add((byte) 'd');
            for (Map.Entry<String, Object> pair : ((LinkedHashMap<String, Object>) obj).entrySet()) {
                out.addAll(encode(pair.getKey()));
                out.addAll(encode(pair.getValue()));
            }
            out.add((byte) 'e');
            return out;
        } else if (obj instanceof ArrayList) {
            out.add((byte) 'l');
            for (Object el : (ArrayList<Object>)obj) {
                out.addAll(encode(el));
            }
            out.add((byte) 'e');
            return out;
        } else if (obj instanceof String) {
            String str = (String) obj;
            String len = String.valueOf(str.length());
            for (int i = 0; i < len.length(); i++) {
                out.add((byte) len.charAt(i));
            }
            out.add((byte) ':');
            for (int i = 0; i < str.length(); i++) {
                out.add((byte) str.charAt(i));
            }
            return out;
        } else if (obj instanceof byte[]) {
            byte[] str = (byte[]) obj;
            String len = String.valueOf(str.length);
            for (int i = 0; i < len.length(); i++) {
                out.add((byte) len.charAt(i));
            }
            out.add((byte) ':');
            for (int i = 0; i < str.length; i++) {
                out.add(str[i]);
            }
            return out;
        } else if (obj instanceof Long) {
            out.add((byte) 'i');
            String num = String.valueOf((Long) obj);
            for (int i = 0; i < num.length(); i++) {
                out.add((byte) num.charAt(i));
            }
            out.add((byte) 'e');
            return out;
        } else {
            throw new DataFormatException("The supplied object cannot be encoded. Invalid structure.");
        }
    }
}