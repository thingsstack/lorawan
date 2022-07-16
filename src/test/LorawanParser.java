package test;
import it.unipr.netsec.ipstack.analyzer.LibpcapReader;
import it.unipr.netsec.ipstack.analyzer.LibpcapRecord;
import it.unipr.netsec.ipstack.ethernet.EthPacket;
import it.unipr.netsec.ipstack.ip4.Ip4Packet;
import it.unipr.netsec.ipstack.udp.UdpPacket;
import it.unipr.netsec.thingsstack.lorawan.dragino.DraginoLHT65Payload;
import it.unipr.netsec.thingsstack.lorawan.dragino.DraginoLSE01Payload;
import it.unipr.netsec.thingsstack.lorawan.mac.LorawanDataMessagePayload;
import it.unipr.netsec.thingsstack.lorawan.mac.LorawanJoinAcceptMessage;
import it.unipr.netsec.thingsstack.lorawan.mac.LorawanJoinAcceptMessagePayload;
import it.unipr.netsec.thingsstack.lorawan.mac.LorawanJoinRequestMessagePayload;
import it.unipr.netsec.thingsstack.lorawan.mac.LorawanMacMessage;
import it.unipr.netsec.thingsstack.lorawan.semtech.SemtechJsonObjectPacket;
import it.unipr.netsec.thingsstack.lorawan.semtech.SemtechPacket;

import static java.lang.System.out;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.security.GeneralSecurityException;

import org.zoolu.util.Base64;
import org.zoolu.util.Bytes;
import org.zoolu.util.Flags;


public abstract class LorawanParser {
	
	enum DataType { LHT65_PAYLOAD, LSE01_PAYLOAD, MAC_FRAME };
	
	public static int SEMTECH_LORAWAN_PORT=1700;
	
	static byte[] APP_KEY=null;
	static byte[] SESSION_KEY=null;



	/** Parses application payload.
	 * @param type the type of the payload
	 * @param payload the payload
	 * @return the analyzed payload */
	private static String parseApplicationPayload(DataType type, String payload, boolean base64) {
		byte[] data= base64? Base64.decode(payload) : Bytes.fromHex(payload);
		return parseApplicationPayload(type,data);
	}

	
	/** Parses application payload.
	 * @param type the type of the payload
	 * @param payload the payload
	 * @return the analyzed data */
	private static String parseApplicationPayload(DataType type, byte[] payload) {
		switch (type) {
		case LHT65_PAYLOAD : return new DraginoLHT65Payload(payload).toString();
		case LSE01_PAYLOAD : return new DraginoLSE01Payload(payload).toString();
		default : throw new RuntimeException("Unknown payload format");
		}
		//return null; // never
	}

	
	/** Parses a LoRaWAN MAC message (i.e. a PHYPayload).
	 * @param data the MAC message
	 * @param base64 whether the message is base64 encoded (or hexadecimal)
	 * @return the analyzed data */
	public static String parseLorawanMacMessage(String data, boolean base64) {
		byte[] macMsg= base64? Base64.decode(data) : Bytes.fromHex(data);
		String str="\tMACMessage: "+Bytes.toHex(macMsg)+'\n';
		return str+parseLorawanMacMessage(macMsg);
	}
	

	/** Parses a LoRaWAN MAC message (i.e. PHYPayload).
	 * @param data the MAC message
	 * @return the analyzed data */
	public static String parseLorawanMacMessage(byte[] data) {
		LorawanMacMessage macMessage=LorawanMacMessage.parseMessage(data);
		String str="\t"+macMessage.toString("\n\t");
		int messageType=macMessage.getMType();
		byte[] macPayload=macMessage.getMacPayload();
		if (messageType==LorawanMacMessage.TYPE_JOIN_REQUEST) {
			str+="\n\tJoin Request message payload:";
			LorawanJoinRequestMessagePayload join=new LorawanJoinRequestMessagePayload(macPayload);
			str+="\n\t\t"+join.toString("\n\t\t");		
		}
		else
		if  (messageType==LorawanMacMessage.TYPE_JOIN_ACCEPT && APP_KEY!=null) {
			str+="\n\tJoin Accept message payload:";
			try {
				LorawanJoinAcceptMessage joinAcceptMsg=(LorawanJoinAcceptMessage)macMessage;
				joinAcceptMsg.decrypt(APP_KEY);
				LorawanJoinAcceptMessagePayload joinAcceptPayload=joinAcceptMsg.getDecryptedPayload();
				str+="\n\t\t"+joinAcceptPayload.toString("\n\t\t");									
				str+="\n\t\tMIC: "+Bytes.toHex(joinAcceptMsg.getDecryptedMIC());									
			}
			catch (GeneralSecurityException e) {
				e.printStackTrace();
			}
		}
		else
		if  (messageType==LorawanMacMessage.TYPE_CORFIRMED_DATA_UP || messageType==LorawanMacMessage.TYPE_UNCORFIRMED_DATA_UP || messageType==LorawanMacMessage.TYPE_CORFIRMED_DATA_DOWN || messageType==LorawanMacMessage.TYPE_UNCORFIRMED_DATA_DOWN) {
			str+="\n\tData message payload:";
			LorawanDataMessagePayload dataMessagePayload=new LorawanDataMessagePayload(macPayload);
			if (SESSION_KEY!=null) {
				try {
					dataMessagePayload.decryptFramePayload(SESSION_KEY);
				}
				catch (GeneralSecurityException e) {
					e.printStackTrace();
				}
			}
			str+="\n\t\t"+dataMessagePayload.toString("\n\t\t");
		}
		return str;
	}
	
	
	private static void processHexFile(String hex_file, DataType type, PrintStream out) throws IOException {
		BufferedReader in=new BufferedReader(new FileReader(hex_file));
		String line=in.readLine();
		int count=0;
		while (line!=null && line.length()>0) {
			count++;
			line=line.trim();
			byte[] data=Bytes.fromHex(line);
			if (type==DataType.MAC_FRAME) {
				out.println("payload #"+count);
				try {
					String str=parseLorawanMacMessage(data);
					out.println("\tframe: "+line);
					out.println(str);
				}
				catch (Exception e) {
					out.println("Error in frame: "+line);
					e.printStackTrace();
				}						
			}
			else {
				out.println(parseApplicationPayload(type,data));
			}
			line=in.readLine();
		}
		in.close();
		out.println();
		out.println("Processed "+count+" lines");
	}
	

	public static void processPcapFile(String pcap_file, int lorawan_port, PrintStream out) throws IOException {
		LibpcapReader pcapReader=new LibpcapReader(pcap_file);
		out.println("pcap type: "+pcapReader.getHeader().getLinkType());
		int count=0;
		int i=0;
		for (; pcapReader.hasMore(); i++) {
			LibpcapRecord pr=pcapReader.read();
			EthPacket ethPkt=EthPacket.parseEthPacket(pr.getPacketData());
			if (ethPkt.getType()==EthPacket.ETH_IP4) {
				Ip4Packet ipPkt=Ip4Packet.parseIp4Packet(ethPkt);
				if (ipPkt.getProto()==Ip4Packet.IPPROTO_UDP) {
					UdpPacket udpPkt=UdpPacket.parseUdpPacket(ipPkt);
					if (udpPkt.getSourcePort()==lorawan_port || udpPkt.getDestPort()==lorawan_port) {
						count++;
						byte[] data=udpPkt.getPayload();
						out.println((i+1)+" "+processSemtechPacket(data));
					}
				}
			}
		}
		out.println();
		out.println("Processed "+count+" Semtech packets out of "+i+" packets.");
	}

	
	public static String processSemtechPacket(byte[] data) {
		return processSemtechPacket(data,0,data.length);
	}
	
	
	public static String processSemtechPacket(byte[] buf, int off, int len) {
		SemtechPacket semPkt=SemtechPacket.parseSemtechPacket(buf,off,len);
		int type=semPkt.getType();
		int token=semPkt.getToken();
		String jsonBody=null;
		if (semPkt instanceof SemtechJsonObjectPacket) {
			jsonBody=((SemtechJsonObjectPacket)semPkt).getJsonObject();	
			int index=jsonBody.indexOf("\"data\":");
			if (index>0) {
				String data64=jsonBody.substring(index+7).split("\"")[1];
				jsonBody+="\n\tbase64-MACMessage: "+data64;
				jsonBody+="\n"+parseLorawanMacMessage(data64,true);
			}
		}
		return Bytes.toHex(Bytes.fromInt16(token))+" "+semPkt.getTypeString()+(jsonBody!=null? " "+jsonBody : "");
	}

	
	public static void semtechToLorawan(String pcap_file, int lorawan_port) throws IOException {
		LibpcapReader pcapReader=new LibpcapReader(pcap_file);
		int semtechCount=0;
		int lorawanCount=0;
		int total=0;
		for (; pcapReader.hasMore(); total++) {
			LibpcapRecord pr=pcapReader.read();
			EthPacket ethPkt=EthPacket.parseEthPacket(pr.getPacketData());
			if (ethPkt.getType()==EthPacket.ETH_IP4) {
				Ip4Packet ipPkt=Ip4Packet.parseIp4Packet(ethPkt);
				if (ipPkt.getProto()==Ip4Packet.IPPROTO_UDP) {
					UdpPacket udpPkt=UdpPacket.parseUdpPacket(ipPkt);
					if (udpPkt.getSourcePort()==lorawan_port || udpPkt.getDestPort()==lorawan_port) {
						semtechCount++;
						byte[] data=udpPkt.getPayload();
						SemtechPacket semPkt=SemtechPacket.parseSemtechPacket(data);
						if (semPkt instanceof SemtechJsonObjectPacket) {
							String jsonBody=((SemtechJsonObjectPacket)semPkt).getJsonObject();
							int index=jsonBody.indexOf("\"data\":");
							if (index>0) {
								String macMessageBase64=jsonBody.substring(index+7).split("\"")[1];
								byte[] macMessage=Base64.decode(macMessageBase64);
								out.println(Bytes.toHex(macMessage));
								lorawanCount++;
							}
						}
					}
				}
			}
		}
		out.println();
		out.println("Processed "+total+" packets. Found "+lorawanCount+" LoRaWAN packets out of "+semtechCount+" Semtech packets.");
	}

	
	public static void main(String[] args) throws IOException {
		Flags flags= new Flags(args);
		int lorawan_port= flags.getInteger("-p",SEMTECH_LORAWAN_PORT,"port","UDP port of the Semtech-LoRaWAN trace");
		boolean help= flags.getBoolean("-h","prints this message");
		String macFrame= flags.getString("-m",null, "frame","parses a LoRaWAN MAC frame");
		String payload= flags.getString("-d",null, "frame","parses application payload");
		boolean base64= flags.getBoolean("-b64","whether data is base64 encoded");
		String hexFile= flags.getString("-f",null,"file","parses a file with LoRaWAN frames or payloads");
		String pcapFile= flags.getString("-pcap",null,"file","parses a pcap file");
		boolean semtech_to_lorawan= flags.getBoolean("-semtech","extraxts LoRaWAN payloads from Semtech-LoRaWAN packets in a pcap file");	
		DataType payloadType= DataType.MAC_FRAME;
		if (flags.getBoolean("-LHT65","Dragino LHT65 payload format")) payloadType= DataType.LHT65_PAYLOAD;	
		if (flags.getBoolean("-LSE01","Dragino LSE01 payload format")) payloadType= DataType.LSE01_PAYLOAD;	
		String app_key= flags.getString("-appkey",null,"key","the join/applidcation AppKey");
		String app_s_key= flags.getString("-appskey",null,"key","the application session key AppSKey");
		
		if (help || (macFrame==null && payload==null && hexFile==null && pcapFile==null)) {
			out.println(flags.toUsageString(LorawanParser.class));
			return;
		}
		// else
		
		if (app_key!=null) APP_KEY=Bytes.fromHex(app_key);
		if (app_s_key!=null) SESSION_KEY=Bytes.fromHex(app_s_key);
		
		if (macFrame!=null) {
			out.println(parseLorawanMacMessage(macFrame,base64));
		}
				
		if (payload!=null) {
			out.println(parseApplicationPayload(payloadType,payload,base64));
		}
				
		if (hexFile!=null) {
			processHexFile(hexFile,payloadType,out);
			return;
		}		

		if (pcapFile!=null) {
			if (semtech_to_lorawan) semtechToLorawan(pcapFile,lorawan_port);
			else processPcapFile(pcapFile,lorawan_port,out);
			return;
		}
	}

}
