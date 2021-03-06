//Copyright 2003-2005 Arthur van Hoff, Rick Blair
//Licensed under Apache License version 2.0
//Original license LGPL

package javax.jmdns.impl;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.jmdns.ServiceInfo;
import javax.jmdns.impl.constants.DNSConstants;
import javax.jmdns.impl.constants.DNSRecordClass;
import javax.jmdns.impl.constants.DNSRecordType;

/**
 * DNS record
 *
 * @version %I%, %G%
 * @author Arthur van Hoff, Rick Blair, Werner Randelshofer, Pierre Frisch
 */
public abstract class DNSRecord extends DNSEntry
{
    private static Logger logger = Logger.getLogger(DNSRecord.class.getName());
    private int _ttl;
    private long _created;

    /**
     * This source is mainly for debugging purposes, should be the address that sent this record.
     */
    private InetAddress _source;

    /**
     * Create a DNSRecord with a name, type, clazz, and ttl.
     */
    DNSRecord(String name, DNSRecordType type, DNSRecordClass recordClass, boolean unique, int ttl)
    {
        super(name, type, recordClass, unique);
        this._ttl = ttl;
        this._created = System.currentTimeMillis();
    }

    /**
     * True if this record is the same as some other record.
     */
    @Override
    public boolean equals(Object other)
    {
        return (other instanceof DNSRecord) && sameAs((DNSRecord) other);
    }

    /**
     * True if this record is the same as some other record.
     */
    boolean sameAs(DNSRecord other)
    {
        return super.equals(other) && sameValue(other);
    }

    /**
     * True if this record has the same value as some other record.
     */
    abstract boolean sameValue(DNSRecord other);

    /**
     * True if this record has the same type as some other record.
     */
    boolean sameType(DNSRecord other)
    {
        return _type == other._type;
    }

    /**
     * Handles a query represented by this record.
     *
     * @return Returns true if a conflict with one of the services registered with JmDNS or with the hostname occured.
     */
    abstract boolean handleQuery(JmDNSImpl dns, long expirationTime);

    /**
     * Handles a responserepresented by this record.
     *
     * @return Returns true if a conflict with one of the services registered with JmDNS or with the hostname occured.
     */
    abstract boolean handleResponse(JmDNSImpl dns);

    /**
     * Adds this as an answer to the provided outgoing datagram.
     */
    abstract DNSOutgoing addAnswer(JmDNSImpl dns, DNSIncoming in, InetAddress addr, int port, DNSOutgoing out)
            throws IOException;

    /**
     * True if this record is suppressed by the answers in a message.
     */
    boolean suppressedBy(DNSIncoming msg)
    {
        try
        {
            for (DNSRecord answer : msg.getAllAnswers())
            {
                if (suppressedBy(answer))
                {
                    return true;
                }
            }
            return false;
        }
        catch (ArrayIndexOutOfBoundsException e)
        {
            logger.log(Level.WARNING, "suppressedBy() message " + msg + " exception ", e);
            // msg.print(true);
            return false;
        }
    }

    /**
     * True if this record would be supressed by an answer. This is the case if this record would not have a
     * significantly longer TTL.
     */
    boolean suppressedBy(DNSRecord other)
    {
        if (sameAs(other) && (other._ttl > _ttl / 2))
        {
            return true;
        }
        return false;
    }

    /**
     * Get the expiration time of this record.
     */
    long getExpirationTime(int percent)
    {
        return _created + (percent * _ttl * 10L);
    }

    /**
     * Get the remaining TTL for this record.
     */
    int getRemainingTTL(long now)
    {
        return (int) Math.max(0, (getExpirationTime(100) - now) / 1000);
    }

    /**
     * Check if the record is expired.
     *
     * @param now
     *            update date
     * @return <code>true</code> is the record is expired, <code>false</code> otherwise.
     */
    @Override
    public boolean isExpired(long now)
    {
        return getExpirationTime(100) <= now;
    }

    /**
     * Check if the record is stale, i.e. it has outlived more than half of its TTL.
     *
     * @param now
     *            update date
     * @return <code>true</code> is the record is stale, <code>false</code> otherwise.
     */
    boolean isStale(long now)
    {
        return getExpirationTime(50) <= now;
    }

    /**
     * Reset the TTL of a record. This avoids having to update the entire record in the cache.
     */
    void resetTTL(DNSRecord other)
    {
        _created = other._created;
        _ttl = other._ttl;
    }

    /**
     * Write this record into an outgoing message.
     */
    abstract void write(DNSOutgoing out) throws IOException;

    /**
     * Address record.
     */
    static class Address extends DNSRecord
    {
        private static Logger logger1 = Logger.getLogger(Address.class.getName());
        InetAddress _addr;

        Address(String name, DNSRecordType type, DNSRecordClass recordClass, boolean unique, int ttl, InetAddress addr)
        {
            super(name, type, recordClass, unique, ttl);
            this._addr = addr;
        }

        Address(String name, DNSRecordType type, DNSRecordClass recordClass, boolean unique, int ttl, byte[] rawAddress)
        {
            super(name, type, recordClass, unique, ttl);
            try
            {
                this._addr = InetAddress.getByAddress(rawAddress);
            }
            catch (UnknownHostException exception)
            {
                logger1.log(Level.WARNING, "Address() exception ", exception);
            }
        }

        @Override
        void write(DNSOutgoing out) throws IOException
        {
            if (_addr != null)
            {
                byte[] buffer = _addr.getAddress();
                if (DNSRecordType.TYPE_A.equals(this.getRecordType()))
                {
                    // If we have a type A records we should answer with a IPv4 address
                    if (_addr instanceof Inet4Address)
                    {
                        // All is good
                    }
                    else
                    {
                        // Get the last four bytes
                        byte[] tempbuffer = buffer;
                        buffer = new byte[4];
                        System.arraycopy(tempbuffer, 12, buffer, 0, 4);
                    }
                }
                else
                {
                    // If we have a type AAAA records we should answer with a IPv6 address
                    if (_addr instanceof Inet4Address)
                    {
                        byte[] tempbuffer = buffer;
                        buffer = new byte[16];
                        for (int i = 0; i < 16; i++)
                        {
                            if (i < 11)
                            {
                                buffer[i] = tempbuffer[i - 12];
                            }
                            else
                            {
                                buffer[i] = 0;
                            }
                        }
                    }
                }
                int length = buffer.length;
                out.writeBytes(buffer, 0, length);
            }
        }

        boolean same(DNSRecord other)
        {
            return ((sameName(other)) && ((sameValue(other))));
        }

        boolean sameName(DNSRecord other)
        {
            return _name.equalsIgnoreCase(((Address) other)._name);
        }

        @Override
        boolean sameValue(DNSRecord other)
        {
            return _addr.equals(((Address) other).getAddress());
        }

        InetAddress getAddress()
        {
            return _addr;
        }

        /**
         * Creates a byte array representation of this record. This is needed for tie-break tests according to
         * draft-cheshire-dnsext-multicastdns-04.txt chapter 9.2.
         */
        @Override
        protected void toByteArray(DataOutputStream dout) throws IOException
        {
            super.toByteArray(dout);
            byte[] buffer = _addr.getAddress();
            for (int i = 0; i < buffer.length; i++)
            {
                dout.writeByte(buffer[i]);
            }
        }

        /**
         * Does the necessary actions, when this as a query.
         */
        @Override
        boolean handleQuery(JmDNSImpl dns, long expirationTime)
        {
            DNSRecord.Address dnsAddress = dns.getLocalHost().getDNSAddressRecord(this);
            if (dnsAddress != null)
            {
                if (dnsAddress.sameType(this) && dnsAddress.sameName(this) && (!dnsAddress.sameValue(this)))
                {
                    logger1.finer("handleQuery() Conflicting probe detected. dns state " + dns.getState()
                            + " lex compare " + compareTo(dnsAddress));
                    // Tie-breaker test
                    if (dns.getState().isProbing() && compareTo(dnsAddress) >= 0)
                    {
                        // We lost the tie-break. We have to choose a different name.
                        dns.getLocalHost().incrementHostName();
                        dns.getCache().clear();
                        for (Iterator<ServiceInfo> i = dns.getServices().values().iterator(); i.hasNext();)
                        {
                            ServiceInfoImpl info = (ServiceInfoImpl) i.next();
                            info.revertState();
                        }
                    }
                    dns.revertState();
                    return true;
                }
            }
            return false;
        }

        /**
         * Does the necessary actions, when this as a response.
         */
        @Override
        boolean handleResponse(JmDNSImpl dns)
        {
            DNSRecord.Address dnsAddress = dns.getLocalHost().getDNSAddressRecord(this);
            if (dnsAddress != null)
            {
                if (dnsAddress.sameType(this) && dnsAddress.sameName(this) && (!dnsAddress.sameValue(this)))
                {
                    logger1.finer("handleResponse() Denial detected");

                    if (dns.getState().isProbing())
                    {
                        dns.getLocalHost().incrementHostName();
                        dns.getCache().clear();
                        for (Iterator<ServiceInfo> i = dns.getServices().values().iterator(); i.hasNext();)
                        {
                            ServiceInfoImpl info = (ServiceInfoImpl) i.next();
                            info.revertState();
                        }
                    }
                    dns.revertState();
                    return true;
                }
            }
            return false;
        }

        @Override
        DNSOutgoing addAnswer(JmDNSImpl dns, DNSIncoming in, InetAddress addr, int port, DNSOutgoing out)
                throws IOException
        {
            return out;
        }

        /*
         * (non-Javadoc)
         *
         * @see com.webobjects.discoveryservices.DNSRecord#toString(java.lang.StringBuilder)
         */
        @Override
        public void toString(StringBuilder aLog)
        {
            aLog.append(" address: '" + (_addr != null ? _addr.getHostAddress() : "null") + "'");
        }

    }

    /**
     * Pointer record.
     */
    public static class Pointer extends DNSRecord
    {
        // private static Logger logger = Logger.getLogger(Pointer.class.getName());
        String _alias;

        public Pointer(String name, DNSRecordType type, DNSRecordClass recordClass, boolean unique, int ttl,
                String alias)
        {
            super(name, type, recordClass, unique, ttl);
            this._alias = alias;
        }

        @Override
        void write(DNSOutgoing out) throws IOException
        {
            out.writeName(_alias);
        }

        @Override
        boolean sameValue(DNSRecord other)
        {
            return _alias.equals(((Pointer) other)._alias);
        }

        @Override
        boolean handleQuery(JmDNSImpl dns, long expirationTime)
        {
            // Nothing to do (?)
            // I think there is no possibility for conflicts for this record type?
            return false;
        }

        @Override
        boolean handleResponse(JmDNSImpl dns)
        {
            // Nothing to do (?)
            // I think there is no possibility for conflicts for this record type?
            return false;
        }

        String getAlias()
        {
            return _alias;
        }

        @Override
        DNSOutgoing addAnswer(JmDNSImpl dns, DNSIncoming in, InetAddress addr, int port, DNSOutgoing out)
                throws IOException
        {
            return out;
        }

        /*
         * (non-Javadoc)
         *
         * @see com.webobjects.discoveryservices.DNSRecord#toString(java.lang.StringBuilder)
         */
        @Override
        public void toString(StringBuilder aLog)
        {
            aLog.append(" alias: '" + (_alias != null ? _alias.toString() : "null") + "'");
        }

    }

    public static class Text extends DNSRecord
    {
        // private static Logger logger = Logger.getLogger(Text.class.getName());
        byte[] _text;

        public Text(String name, DNSRecordType type, DNSRecordClass recordClass, boolean unique, int ttl, byte text[])
        {
            super(name, type, recordClass, unique, ttl);
            this._text = text;
        }

        @Override
        void write(DNSOutgoing out) throws IOException
        {
            out.writeBytes(_text, 0, _text.length);
        }

        @Override
        boolean sameValue(DNSRecord other)
        {
            Text txt = (Text) other;
            if (txt._text.length != _text.length)
            {
                return false;
            }
            for (int i = _text.length; i-- > 0;)
            {
                if (txt._text[i] != _text[i])
                {
                    return false;
                }
            }
            return true;
        }

        @Override
        boolean handleQuery(JmDNSImpl dns, long expirationTime)
        {
            // Nothing to do (?)
            // I think there is no possibility for conflicts for this record type?
            return false;
        }

        @Override
        boolean handleResponse(JmDNSImpl dns)
        {
            // Nothing to do (?)
            // Shouldn't we care if we get a conflict at this level?
            /*
             * ServiceInfo info = (ServiceInfo) dns.services.get(name.toLowerCase()); if (info != null) { if (!
             * Arrays.equals(text,info.text)) { info.revertState(); return true; } }
             */
            return false;
        }

        @Override
        DNSOutgoing addAnswer(JmDNSImpl dns, DNSIncoming in, InetAddress addr, int port, DNSOutgoing out)
                throws IOException
        {
            return out;
        }

        /*
         * (non-Javadoc)
         *
         * @see com.webobjects.discoveryservices.DNSRecord#toString(java.lang.StringBuilder)
         */
        @Override
        public void toString(StringBuilder aLog)
        {
            aLog.append(" text: '" + ((_text.length > 10) ? new String(_text, 0, 7) + "..." : new String(_text)) + "'");
        }

    }

    /**
     * Service record.
     */
    public static class Service extends DNSRecord
    {
        private static Logger logger1 = Logger.getLogger(Service.class.getName());
        int _priority;
        int _weight;
        int _port;
        String _server;

        public Service(String name, DNSRecordType type, DNSRecordClass recordClass, boolean unique, int ttl,
                int priority, int weight, int port, String server)
        {
            super(name, type, recordClass, unique, ttl);
            this._priority = priority;
            this._weight = weight;
            this._port = port;
            this._server = server;
        }

        @Override
        void write(DNSOutgoing out) throws IOException
        {
            out.writeShort(_priority);
            out.writeShort(_weight);
            out.writeShort(_port);
            if (DNSIncoming.USE_DOMAIN_NAME_FORMAT_FOR_SRV_TARGET)
            {
                out.writeName(_server, false);
            }
            else
            {
                out.writeUTF(_server, 0, _server.length());

                // add a zero byte to the end just to be safe, this is the strange form
                // used by the BonjourConformanceTest
                out.writeByte(0);
            }
        }

        @Override
        protected void toByteArray(DataOutputStream dout) throws IOException
        {
            super.toByteArray(dout);
            dout.writeShort(_priority);
            dout.writeShort(_weight);
            dout.writeShort(_port);
            try
            {
                dout.write(_server.getBytes("UTF-8"));
            }
            catch (UnsupportedEncodingException exception)
            {
                /* UTF-8 is always present */
            }
        }

        String getServer()
        {
            return _server;
        }

        @Override
        boolean sameValue(DNSRecord other)
        {
            Service s = (Service) other;
            return (_priority == s._priority) && (_weight == s._weight) && (_port == s._port)
                    && _server.equals(s._server);
        }

        @Override
        boolean handleQuery(JmDNSImpl dns, long expirationTime)
        {
            ServiceInfoImpl info = (ServiceInfoImpl) dns.getServices().get(_name.toLowerCase());
            if (info != null && (_port != info.getPort() || !_server.equalsIgnoreCase(dns.getLocalHost().getName())))
            {
                logger1.finer("handleQuery() Conflicting probe detected from: " + getRecordSource());
                DNSRecord.Service localService = new DNSRecord.Service(info.getQualifiedName(), DNSRecordType.TYPE_SRV,
                        DNSRecordClass.CLASS_IN, DNSRecordClass.UNIQUE, DNSConstants.DNS_TTL, info.getPriority(), info
                                .getWeight(), info.getPort(), dns.getLocalHost().getName());

                // This block is useful for debugging race conditions when jmdns is respoding to
                // itself.
                try
                {
                    if (dns.getInterface().equals(getRecordSource()))
                    {
                        logger1.warning("Got conflicting probe from ourselves\n" + "incoming: " + this.toString()
                                + "\n" + "local   : " + localService.toString());
                    }
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }

                int comparison = this.compareTo(localService);

                if (comparison == 0)
                {
                    // the 2 records are identical this probably means we are seeing our own record.
                    // With mutliple interfaces on a single computer it is possible to see our
                    // own records come in on different interfaces than the ones they were sent on.
                    // see section "10. Conflict Resolution" of mdns draft spec.
                    logger1.finer("handleQuery() Ignoring a identical service query");
                    return false;
                }

                // Tie breaker test
                if (info.getState().isProbing() && comparison > 0)
                {
                    // We lost the tie break
                    String oldName = info.getQualifiedName().toLowerCase();
                    info.setName(dns.incrementName(info.getName()));
                    dns.getServices().remove(oldName);
                    dns.getServices().put(info.getQualifiedName().toLowerCase(), info);
                    logger1.finer("handleQuery() Lost tie break: new unique name chosen:" + info.getName());

                    // We revert the state to start probing again with the new name
                    info.revertState();
                }
                else
                {
                    // We won the tie break, so this conflicting probe should be ignored
                    // See paragraph 3 of section 9.2 in mdns draft spec
                    return false;
                }

                return true;

            }
            return false;
        }

        @Override
        boolean handleResponse(JmDNSImpl dns)
        {
            ServiceInfoImpl info = (ServiceInfoImpl) dns.getServices().get(_name.toLowerCase());
            if (info != null && (_port != info.getPort() || !_server.equalsIgnoreCase(dns.getLocalHost().getName())))
            {
                logger1.finer("handleResponse() Denial detected");

                if (info.getState().isProbing())
                {
                    String oldName = info.getQualifiedName().toLowerCase();
                    info.setName(dns.incrementName(info.getName()));
                    dns.getServices().remove(oldName);
                    dns.getServices().put(info.getQualifiedName().toLowerCase(), info);
                    logger1.finer("handleResponse() New unique name chose:" + info.getName());

                }
                info.revertState();
                return true;
            }
            return false;
        }

        @Override
        DNSOutgoing addAnswer(JmDNSImpl dns, DNSIncoming in, InetAddress addr, int port, DNSOutgoing out)
                throws IOException
        {
            ServiceInfoImpl info = (ServiceInfoImpl) dns.getServices().get(_name.toLowerCase());
            if (info != null)
            {
                if (this._port == info.getPort() != _server.equals(dns.getLocalHost().getName()))
                {
                    return dns.addAnswer(in, addr, port, out, new DNSRecord.Service(info.getQualifiedName(),
                            DNSRecordType.TYPE_SRV, DNSRecordClass.CLASS_IN, DNSRecordClass.UNIQUE,
                            DNSConstants.DNS_TTL, info.getPriority(), info.getWeight(), info.getPort(), dns
                                    .getLocalHost().getName()));
                }
            }
            return out;
        }

        /*
         * (non-Javadoc)
         *
         * @see com.webobjects.discoveryservices.DNSRecord#toString(java.lang.StringBuilder)
         */
        @Override
        public void toString(StringBuilder aLog)
        {
            aLog.append(" server: '" + _server + ":" + _port + "'");
        }
    }

    public void setRecordSource(InetAddress source)
    {
        this._source = source;
    }

    public InetAddress getRecordSource()
    {
        return _source;
    }

    public String toString(String other)
    {
        return toString("record", _ttl + "/" + getRemainingTTL(System.currentTimeMillis()) + ", " + other);
    }

    public void setTTL(int ttl)
    {
        this._ttl = ttl;
    }

    public int getTTL()
    {
        return _ttl;
    }
}
