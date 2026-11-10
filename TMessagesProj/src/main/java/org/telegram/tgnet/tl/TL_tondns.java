package org.telegram.tgnet.tl;

import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.TLMethod;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

import java.util.ArrayList;

/**
 * StaticGram private overlay (server _schema/tondns.tl, docs/ton-dns.md): .ton domains on the
 * StaticGram chain. Names in answers are full ("alice.ton"); amounts are nanoton (GRAM).
 */
public class TL_tondns {

    /** tondns.domainCheck#047ed8d9 name:string available:Bool price:long = tondns.DomainCheck */
    public static class TL_domainCheck extends TLObject {
        public static final int constructor = 0x047ed8d9;

        public String name;
        public boolean available;
        public long price;

        public static TL_domainCheck TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_domainCheck.class, constructor == TL_domainCheck.constructor ? new TL_domainCheck() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            name = stream.readString(exception);
            available = stream.readBool(exception);
            price = stream.readInt64(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(name);
            stream.writeBool(available);
            stream.writeInt64(price);
        }
    }

    /**
     * tondns.domain#dfce26f1 flags:# primary:flags.0?true minted:flags.1?true name:string item_address:string
     * owner_address:string wallet_address:string price:long purchased_at:int = tondns.Domain
     */
    public static class TL_domain extends TLObject {
        public static final int constructor = 0xdfce26f1;

        public int flags;
        public boolean primary;
        public boolean minted;
        public String name;
        public String item_address;
        public String owner_address;
        public String wallet_address;
        public long price;
        public int purchased_at;

        public static TL_domain TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_domain.class, constructor == TL_domain.constructor ? new TL_domain() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            primary = (flags & 1) != 0;
            minted = (flags & 2) != 0;
            name = stream.readString(exception);
            item_address = stream.readString(exception);
            owner_address = stream.readString(exception);
            wallet_address = stream.readString(exception);
            price = stream.readInt64(exception);
            purchased_at = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            flags = primary ? (flags | 1) : (flags & ~1);
            flags = minted ? (flags | 2) : (flags & ~2);
            stream.writeInt32(flags);
            stream.writeString(name);
            stream.writeString(item_address);
            stream.writeString(owner_address);
            stream.writeString(wallet_address);
            stream.writeInt64(price);
            stream.writeInt32(purchased_at);
        }
    }

    /** tondns.domains#a6625118 domains:Vector<tondns.Domain> = tondns.Domains */
    public static class TL_domains extends TLObject {
        public static final int constructor = 0xa6625118;

        public ArrayList<TL_domain> domains = new ArrayList<>();

        public static TL_domains TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_domains.class, constructor == TL_domains.constructor ? new TL_domains() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            domains = Vector.deserialize(stream, TL_domain::TLdeserialize, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            Vector.serialize(stream, domains);
        }
    }

    /** tondns.buyResult#40c947be domain:tondns.Domain balance:long replayed:Bool = tondns.BuyResult */
    public static class TL_buyResult extends TLObject {
        public static final int constructor = 0x40c947be;

        public TL_domain domain;
        public long balance;
        public boolean replayed;

        public static TL_buyResult TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_buyResult.class, constructor == TL_buyResult.constructor ? new TL_buyResult() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            domain = TL_domain.TLdeserialize(stream, stream.readInt32(exception), exception);
            balance = stream.readInt64(exception);
            replayed = stream.readBool(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            domain.serializeToStream(stream);
            stream.writeInt64(balance);
            stream.writeBool(replayed);
        }
    }

    /** tondns.resolved#2a6e17ca name:string address:string = tondns.Resolved */
    public static class TL_resolved extends TLObject {
        public static final int constructor = 0x2a6e17ca;

        public String name;
        public String address;

        public static TL_resolved TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_resolved.class, constructor == TL_resolved.constructor ? new TL_resolved() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            name = stream.readString(exception);
            address = stream.readString(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(name);
            stream.writeString(address);
        }
    }

    /** tondns.addressDomain#f8cd4a59 address:string domain:string = tondns.AddressDomain */
    public static class TL_addressDomain extends TLObject {
        public static final int constructor = 0xf8cd4a59;

        public String address;
        public String domain;

        public static TL_addressDomain TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_addressDomain.class, constructor == TL_addressDomain.constructor ? new TL_addressDomain() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            address = stream.readString(exception);
            domain = stream.readString(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(address);
            stream.writeString(domain);
        }
    }

    /** tondns.addressDomains#ad91fd30 domains:Vector<tondns.AddressDomain> = tondns.AddressDomains */
    public static class TL_addressDomains extends TLObject {
        public static final int constructor = 0xad91fd30;

        public ArrayList<TL_addressDomain> domains = new ArrayList<>();

        public static TL_addressDomains TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_addressDomains.class, constructor == TL_addressDomains.constructor ? new TL_addressDomains() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            domains = Vector.deserialize(stream, TL_addressDomain::TLdeserialize, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            Vector.serialize(stream, domains);
        }
    }

    // ===== functions =====

    /** tondns.checkDomain#ce407588 name:string = tondns.DomainCheck */
    public static class TL_checkDomain extends TLMethod<TL_domainCheck> {
        public static final int constructor = 0xce407588;

        public String name;

        @Override
        public TL_domainCheck deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_domainCheck.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(name);
        }
    }

    /** tondns.buyDomain#dfde8b00 name:string price:long request_key:string = tondns.BuyResult */
    public static class TL_buyDomain extends TLMethod<TL_buyResult> {
        public static final int constructor = 0xdfde8b00;

        public String name;
        public long price;
        public String request_key;

        @Override
        public TL_buyResult deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_buyResult.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(name);
            stream.writeInt64(price);
            stream.writeString(request_key);
        }
    }

    /** tondns.getMyDomains#b1ba10e8 = tondns.Domains */
    public static class TL_getMyDomains extends TLMethod<TL_domains> {
        public static final int constructor = 0xb1ba10e8;

        @Override
        public TL_domains deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_domains.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
        }
    }

    /** tondns.setPrimaryDomain#d0551531 name:string = Bool ("" clears) */
    public static class TL_setPrimaryDomain extends TLMethod<TLRPC.Bool> {
        public static final int constructor = 0xd0551531;

        public String name;

        @Override
        public TLRPC.Bool deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TLRPC.Bool.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(name == null ? "" : name);
        }
    }

    /** tondns.setWalletRecord#b5cb23b7 flags:# name:string address:flags.0?string = tondns.Domain (no address = own wallet) */
    public static class TL_setWalletRecord extends TLMethod<TL_domain> {
        public static final int constructor = 0xb5cb23b7;

        public int flags;
        public String name;
        public String address;

        @Override
        public TL_domain deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_domain.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            flags = address != null ? (flags | 1) : (flags & ~1);
            stream.writeInt32(flags);
            stream.writeString(name);
            if ((flags & 1) != 0) {
                stream.writeString(address);
            }
        }
    }

    /** tondns.resolve#3465f9a6 name:string = tondns.Resolved */
    public static class TL_resolve extends TLMethod<TL_resolved> {
        public static final int constructor = 0x3465f9a6;

        public String name;

        @Override
        public TL_resolved deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_resolved.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(name);
        }
    }

    /** tondns.getDomainsForAddresses#6c81e48d addresses:Vector<string> = tondns.AddressDomains (≤200, only matches) */
    public static class TL_getDomainsForAddresses extends TLMethod<TL_addressDomains> {
        public static final int constructor = 0x6c81e48d;

        public ArrayList<String> addresses = new ArrayList<>();

        @Override
        public TL_addressDomains deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_addressDomains.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            Vector.serializeString(stream, addresses);
        }
    }

    // ===== Resale (server docs/ton-dns.md «Resale», StaticGram clients after 7166) =====

    /** tondns.listing#286f28d8 name:string seller_id:long price:long item_address:string listed_at:int = tondns.Listing */
    public static class TL_listing extends TLObject {
        public static final int constructor = 0x286f28d8;

        public String name;
        public long seller_id;
        public long price;
        public String item_address;
        public int listed_at;

        public static TL_listing TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_listing.class, constructor == TL_listing.constructor ? new TL_listing() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            name = stream.readString(exception);
            seller_id = stream.readInt64(exception);
            price = stream.readInt64(exception);
            item_address = stream.readString(exception);
            listed_at = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(name);
            stream.writeInt64(seller_id);
            stream.writeInt64(price);
            stream.writeString(item_address);
            stream.writeInt32(listed_at);
        }
    }

    /**
     * tondns.listings#29ecf728 count:int listings:Vector<tondns.Listing> users:Vector<User>
     * min_price:long max_price:long fee_percent:int = tondns.Listings
     */
    public static class TL_listings extends TLObject {
        public static final int constructor = 0x29ecf728;

        public int count;
        public ArrayList<TL_listing> listings = new ArrayList<>();
        public ArrayList<TLRPC.User> users = new ArrayList<>();
        public long min_price;
        public long max_price;
        public int fee_percent;

        public static TL_listings TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_listings.class, constructor == TL_listings.constructor ? new TL_listings() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            count = stream.readInt32(exception);
            listings = Vector.deserialize(stream, TL_listing::TLdeserialize, exception);
            users = Vector.deserialize(stream, TLRPC.User::TLdeserialize, exception);
            min_price = stream.readInt64(exception);
            max_price = stream.readInt64(exception);
            fee_percent = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(count);
            Vector.serialize(stream, listings);
            Vector.serialize(stream, users);
            stream.writeInt64(min_price);
            stream.writeInt64(max_price);
            stream.writeInt32(fee_percent);
        }
    }

    /**
     * tondns.domainCheckEx#dddc5e96 flags:# available:flags.0?true name:string price:long
     * listing:flags.1?tondns.Listing users:Vector<User> = tondns.DomainCheckEx
     */
    public static class TL_domainCheckEx extends TLObject {
        public static final int constructor = 0xdddc5e96;

        public int flags;
        public boolean available;
        public String name;
        public long price;
        public TL_listing listing;
        public ArrayList<TLRPC.User> users = new ArrayList<>();

        public static TL_domainCheckEx TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_domainCheckEx.class, constructor == TL_domainCheckEx.constructor ? new TL_domainCheckEx() : null, stream, constructor, exception);
        }

        /** The plain check of an older server, as an Ex answer without a listing. */
        public static TL_domainCheckEx of(TL_domainCheck check) {
            final TL_domainCheckEx ex = new TL_domainCheckEx();
            ex.available = check.available;
            ex.name = check.name;
            ex.price = check.price;
            return ex;
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            available = (flags & 1) != 0;
            name = stream.readString(exception);
            price = stream.readInt64(exception);
            if ((flags & 2) != 0) {
                listing = TL_listing.TLdeserialize(stream, stream.readInt32(exception), exception);
            }
            users = Vector.deserialize(stream, TLRPC.User::TLdeserialize, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            flags = available ? (flags | 1) : (flags & ~1);
            flags = listing != null ? (flags | 2) : (flags & ~2);
            stream.writeInt32(flags);
            stream.writeString(name);
            stream.writeInt64(price);
            if (listing != null) listing.serializeToStream(stream);
            Vector.serialize(stream, users);
        }
    }

    /** tondns.checkDomainEx#38c639e7 name:string = tondns.DomainCheckEx */
    public static class TL_checkDomainEx extends TLMethod<TL_domainCheckEx> {
        public static final int constructor = 0x38c639e7;

        public String name;

        @Override
        public TL_domainCheckEx deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_domainCheckEx.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(name);
        }
    }

    /** tondns.listForSale#e7f1f9ae name:string price:long = tondns.Listing (again = new price) */
    public static class TL_listForSale extends TLMethod<TL_listing> {
        public static final int constructor = 0xe7f1f9ae;

        public String name;
        public long price;

        @Override
        public TL_listing deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_listing.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(name);
            stream.writeInt64(price);
        }
    }

    /** tondns.cancelSale#a4cdd903 name:string = Bool */
    public static class TL_cancelSale extends TLMethod<TLRPC.Bool> {
        public static final int constructor = 0xa4cdd903;

        public String name;

        @Override
        public TLRPC.Bool deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TLRPC.Bool.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(name);
        }
    }

    /** tondns.buyListed#c2f511da name:string expected_price:long request_key:string = tondns.BuyResult */
    public static class TL_buyListed extends TLMethod<TL_buyResult> {
        public static final int constructor = 0xc2f511da;

        public String name;
        public long expected_price;
        public String request_key;

        @Override
        public TL_buyResult deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_buyResult.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeString(name);
            stream.writeInt64(expected_price);
            stream.writeString(request_key);
        }
    }

    /** tondns.getListings#b4961c29 offset:int limit:int sort:string = tondns.Listings (sort: date | price_asc | price_desc | name) */
    public static class TL_getListings extends TLMethod<TL_listings> {
        public static final int constructor = 0xb4961c29;

        public int offset;
        public int limit;
        public String sort;

        @Override
        public TL_listings deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_listings.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(offset);
            stream.writeInt32(limit);
            stream.writeString(sort == null ? "date" : sort);
        }
    }

    /** tondns.getMyListings#ecf0d678 = tondns.Listings */
    public static class TL_getMyListings extends TLMethod<TL_listings> {
        public static final int constructor = 0xecf0d678;

        @Override
        public TL_listings deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_listings.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
        }
    }
}
