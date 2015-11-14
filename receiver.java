import java.io.*; 
import java.net.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

class receiver { 
    
	public static void main(String[] args) throws Exception{
	    //Verify the 4 args first
        if(args.length!=4){
            System.out.println("You must enter 4 values as input (Address, Data Port, Ack Port, File Name)");
            System.exit(0);
        }
        String emulatorAddress = args[0];
        int ackPort = Integer.parseInt(args[1]);
        int dataPort = Integer.parseInt(args[2]);
        String fileName = args[3];
        
        boolean eot =false;
        int seq =-1;
        
        DatagramSocket udpSocketOut = new DatagramSocket();
        DatagramSocket udpSocketIn = new DatagramSocket(dataPort);
        InetAddress IPAddress = InetAddress.getByName(emulatorAddress);
        byte[] receiveData = new byte[512];
        
        PrintWriter seqWriter = new PrintWriter("arrival.log", "UTF-8");
        PrintWriter fileWriter = new PrintWriter(fileName, "UTF-8");
        
        while(eot==false){
            //System.out.println("Waiting for packet");
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length); //input packets
            udpSocketIn.receive(receivePacket);//receive packets from the emulator
            //now check out that packet
            //System.out.println("received a packet!");
            packet newPacket = null;
            try {
                newPacket = newPacket.parseUDPdata(receiveData);
            } catch (Exception e){
                //packet parsing exception
            }
            if(newPacket !=null){
                int curType = newPacket.getType();
                int curSeq = newPacket.getSeqNum();
                //System.out.println("Seq given: "+curSeq+" expected seq: "+((seq+1)%32));
                if(curSeq==(seq+1)%32){//this is the one!
                    seq=curSeq; //new recent packet num
                    if(curType==1){ //reg packet
                        //System.out.println("sending back an ACK");
                        sendAck(seq, udpSocketOut, IPAddress, ackPort);
                        //log the seq num
                        seqWriter.println(seq);
                        //also write the contents to file
						String content = new String(newPacket.getData());
						//System.out.println("Data length was "+newPacket.getLength());
						//System.out.println("Content: "+content);
                        fileWriter.print(content);
						fileWriter.flush();
                    } else if(curType==2){//eot
                        //System.out.println("sending back an eot");
                        sendEot(seq, udpSocketOut, IPAddress, ackPort);
                        eot=true;
						
                    }
                } else {
                    //System.out.println("not the expected packet. sending old ack: "+seq);
                    sendAck(seq, udpSocketOut, IPAddress, ackPort);//resend our most recent ack
                }
            }
            
        }
        
        udpSocketOut.close();
        udpSocketIn.close();
        
        seqWriter.close();
        fileWriter.close();
        
	}
	
	public static void sendAck(int seq, DatagramSocket socket, InetAddress address, int port){
	    try {
			//System.out.println("Inside ack sender with seq "+seq);
            packet newPacket = packet.createACK(seq); //create our packet
            //convert our data to a byte array
            byte byteData[] = newPacket.getUDPdata();
            
            //send it
            DatagramPacket sendPacket = new DatagramPacket(byteData, byteData.length, address, port);
            socket.send(sendPacket);
            
        } catch (Exception e){
            System.out.println("Error sending packet "+e);
        }    
	}
	
	public static void sendEot(int seq, DatagramSocket socket, InetAddress address, int port){
	    try {
			//System.out.println("Sending EOT");
            packet newPacket = packet.createEOT(seq); //create our packet
            //convert our data to a byte array
            byte byteData[] = newPacket.getUDPdata();
            
            //send it
            DatagramPacket sendPacket = new DatagramPacket(byteData, byteData.length, address, port);
            socket.send(sendPacket);
            //System.out.println("EOT now sent");
        } catch (Exception e){
            System.out.println("Error sending packet "+e);
        }    
	}

	
	
}
