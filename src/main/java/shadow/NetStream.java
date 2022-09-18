package shadow;

import jnr.constants.platform.*;
import jnr.enxio.channels.Native;
import jnr.ffi.LastError;
import jnr.ffi.*;
import jnr.ffi.annotations.In;
import jnr.ffi.annotations.Out;
import jnr.ffi.annotations.Transient;
import jnr.posix.Timeval;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class NetStream {

    static final String[] libnames = Platform.getNativePlatform().getOS() == Platform.OS.SOLARIS
            ? new String[] { "socket", "nsl", "c" }
            : new String[] { Platform.getNativePlatform().getStandardCLibraryName() };
    static final LibC libc = LibraryLoader.loadLibrary(LibC.class, Collections.emptyMap(), libnames);
    static final jnr.ffi.Runtime runtime = jnr.ffi.Runtime.getSystemRuntime();

    public static class SockAddr extends Struct {
        public SockAddr() {
            super(runtime);
        }
    }

    static class BSDSockAddrIN extends SockAddr {

        public final Unsigned8 sin_len = new Unsigned8();
        public final Unsigned8 sin_family = new Unsigned8();
        public final Unsigned16 sin_port = new Unsigned16();
        public final Unsigned32 sin_addr = new Unsigned32();
        public final Padding sin_zero = new Padding(NativeType.SCHAR, 8);
    }

    static class SockAddrIN extends SockAddr {

        public final Unsigned16 sin_family = new Unsigned16();
        public final Unsigned16 sin_port = new Unsigned16();
        public final Unsigned32 sin_addr = new Unsigned32();
        public final Padding sin_zero = new Padding(NativeType.SCHAR, 8);
    }

    public interface LibC extends jnr.posix.LibC {
        int AF_INET = jnr.constants.platform.AddressFamily.AF_INET.intValue();
        int SOCK_STREAM = jnr.constants.platform.Sock.SOCK_STREAM.intValue();

        int socket(int domain, int type, int protocol);
        int listen(int fd, int backlog);
        int bind(int fd, SockAddr addr, int len);
        int accept(int fd, @Out SockAddr addr, int[] len);
        int connect(int s, @In @Transient SockAddr name, int namelen);
        int setsockopt(int fd, int level, int optname, @In Timeval optval, int optlen);

    }
    static short htons(short val) {
        return Short.reverseBytes(val);
    }

    int sock;
    byte[] wbuf = new byte[0];
    byte[] rbuf = new byte[0];
    int stat;
    int errc;
    Set<Integer> errd = new HashSet<>() {{
        add(Errno.EINPROGRESS.value());
        add(Errno.EALREADY.value());
        add(Errno.EWOULDBLOCK.value());
    }};

    Set<Integer> conn = new HashSet<>() {{
        add(Errno.EISCONN.value());
        add(10057);
        add(10053);
    }};

    private int try_connect() {
        if (stat == 2) {
            return 1;
        }
        if (stat != 1) {
            return -1;
        }
        libc.read(sock, new byte[]{}, 0);
        int code = LastError.getLastError(runtime);
        if (code != 0) {
            if (conn.contains(code)) {
                return 0;
            }
            if (errd.contains(code)) {
                stat = 2;
                rbuf = new byte[0];
                return 1;
            }
            close();
            return -1;
        }
        stat = 2;
        return 1;
    }

    public static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }
        byte[] result = new byte[length];
        int pos = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, pos, array.length);
            pos += array.length;
        }
        return result;
    }

    public int try_recv() {
        byte[] rdata = new byte[0];
        while (true) {
            byte[] text = new byte[1024];
            int read = libc.read(sock, text, 1024);
            if(read == 0) {
                // eof
                break;
            }
            int code = LastError.getLastError(runtime);
            if (!errd.contains(code)) {
                errc = code;
                close();
                return -1;
            }

            rdata = concat(rdata, text);
        }
        rbuf = concat(rbuf, rdata);
        return rdata.length;
    }

    public int try_send() {
        int wsize = 0;
        if (wbuf.length == 0) {
            return 0;
        }
        wsize = libc.write(sock, wbuf, wbuf.length);
        int code = LastError.getLastError(runtime);
        if (!errd.contains(code)) {
            errc = code;
            close();
            return -1;
        }
        wbuf = Arrays.copyOfRange(wbuf, wsize, wbuf.length);
        return wsize;
    }

    public int connect(String address, int port) throws UnknownHostException {
        sock = libc.socket(LibC.AF_INET, LibC.SOCK_STREAM, 0);
        Native.setBlocking(sock, false);
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.order(ByteOrder.nativeOrder());
        buf.putInt(1).flip();
        libc.setsockopt(sock, SocketLevel.SOL_SOCKET.intValue(), SocketOption.SO_KEEPALIVE.intValue(), buf, buf.remaining());
        InetAddress inet_addr = InetAddress.getByName(address);
        SockAddrIN sockaddr = new SockAddrIN();
        sockaddr.sin_family.set((byte) LibC.AF_INET);
        sockaddr.sin_addr.set(ByteBuffer.wrap(inet_addr.getAddress()).getInt());
        sockaddr.sin_port.set(htons((short) port));
        libc.connect(sock, sockaddr, SockAddr.size(sockaddr));
        stat = 1;
        wbuf = new byte[0];
        rbuf = new byte[0];
        errc = 0;
        return 0;
    }

    public int close() {
        stat = 0;
        if (sock == 0) {
            return 0;
        }
        try {
            libc.close(sock);
        } catch (Exception ignore) {

        }
        sock = 0;
        return 0;
    }

    public void assign(int sock) {
        close();
        this.sock = sock;
        Native.setBlocking(sock, false);
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.order(ByteOrder.nativeOrder());
        buf.putInt(1).flip();
        libc.setsockopt(sock, SocketLevel.SOL_SOCKET.intValue(), SocketOption.SO_KEEPALIVE.intValue(), buf, buf.remaining());
        stat = 2;
    }

    public int process() {
        if (stat == 0) {
            return 0;
        }
        if (stat == 1) {
            try_connect();
        }
        if (stat == 2) {
            try_recv();
        }
        if (stat == 2) {
            try_send();
        }
        return 0;
    }

    public int status() {
        return stat;
    }

    public int error() {
        return errc;
    }

    public int sendraw(byte[] data) {
        wbuf = concat(wbuf, data);
        process();
        return 0;
    }

    public byte[] peekraw(int size) {
        process();
        if (rbuf.length == 0) {
            return new byte[0];
        }
        if (size > rbuf.length) {
            size = rbuf.length;
        }
        rbuf = Arrays.copyOfRange(rbuf, 0, size);
        return rbuf;
    }

    public byte[] recvraw(int size) {
        byte[] rdata = peekraw(size);
        size = rdata.length;
        rbuf = Arrays.copyOfRange(rbuf, size, rbuf.length);
        return rdata;
    }

    public int send(byte[] data) {
        byte[] wsize = ByteBuffer.allocate(4).putInt(data.length).array();
        sendraw(concat(wsize, data));
        return 0;
    }

    public byte[] recv() {
        byte[] rsize = peekraw(4);
        if (rsize.length < 4) {
            return new byte[0];
        }
        int size = ByteBuffer.allocate(4).put(rsize).getInt();
        if (rbuf.length < size) {
            return new byte[0];
        }
        recvraw(4);
        return recvraw(size);
    }

    public int nodelay(int nodelay) {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.order(ByteOrder.nativeOrder());
        buf.putInt(nodelay).flip();
        libc.setsockopt(sock, IPProto.IPPROTO_TCP.intValue(), TCP.TCP_NODELAY.intValue(), buf, buf.remaining());
        return 0;
    }

    public static class NetHost {

    }
}
