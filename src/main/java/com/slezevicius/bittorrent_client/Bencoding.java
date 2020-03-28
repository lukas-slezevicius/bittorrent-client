import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

public class Bencoding {
    private int idx;
    private byte[] input;

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

    public Object decode() {
        if (input[idx] == 'i') {
            //Bencoded integers
            idx += 1;
            int a = idx;
            while (input[idx] != 'e') {
                idx += 1;
            }
            int b = idx;
            idx += 1;
            return Integer.parseInt(new String(input, a, b - a, StandardCharsets.UTF_8));
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
            HashMap<String, Object> map = new HashMap<String, Object>();
            idx += 1;
            while (input[idx] != 'e') {
                Object key = decode();
                Object value = decode();
                map.put(new String((byte[]) key), value);
            }
            return map;
        } else {
            return null;
        }
    }

    public static void main(String args[]) {
        String input = "d2:abi12534e4:hahai-12e3:MYRli1ei2ed2:jji59eeee";
        Bencoding b = new Bencoding(input);
        Object out = b.decode();
        System.out.println(out);
    }
}