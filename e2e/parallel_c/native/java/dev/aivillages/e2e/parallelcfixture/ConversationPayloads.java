package dev.aivillages.e2e.parallelcfixture;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Test-only bounded payload types, independent of legacy chat/command handlers. */
public final class ConversationPayloads {
    private ConversationPayloads() { }
    public record Request(String json) implements CustomPacketPayload {
        public Request { if(json==null||json.length()>16384)throw new IllegalArgumentException("payload bound"); }
        public static final Type<Request> TYPE=new Type<>(Identifier.parse("parallel_c_fixture:conversation_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=StreamCodec.of((buf,value)->buf.writeUtf(value.json(),16384),buf->new Request(buf.readUtf(16384)));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Response(String json) implements CustomPacketPayload {
        public Response { if(json==null||json.length()>16384)throw new IllegalArgumentException("payload bound"); }
        public static final Type<Response> TYPE=new Type<>(Identifier.parse("parallel_c_fixture:conversation_response"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Response> CODEC=StreamCodec.of((buf,value)->buf.writeUtf(value.json(),16384),buf->new Response(buf.readUtf(16384)));
        public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
}
