import java.io.*; 
import java.net.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


class sender { 

    private static int maxSize =500;

	public static int base=0;
	public static boolean eotReceived=false;
    
	public static void main(String[] args) throws Exception{
	    //Verify the 4 args first
        if(args.length!=4){
            System.out.println("You must enter 4 values as input (Address, Data Port, Ack Port, File Name)");
            System.exit(0);
        }
        String emulatorAddress = args[0];
        int dataPort = Integer.parseInt(args[1]);
        int ackPort = Integer.parseInt(args[2]);
        String fileName = args[3];
        
        int N = 10; //window size
        int seq=-1;
        
        long timeOut =0;
        boolean eotSent = false;
        
        
        //open up the file
        File file = new File(fileName);
        FileInputStream fin =null;
        
        //as an easy way to monitor whats been read I just track the length so far
        double fileSize =(double)file.length();
        int curRead =0;
        
        int packetsSize =(int)Math.ceil(fileSize/maxSize);
        String packets[]=new String[32]; //just hard code to 32
        //System.out.println("we will need "+packetsSize+" for a file of "+fileSize);
        try {
            fin = new FileInputStream(file);
            
        } catch (FileNotFoundException e){
            System.out.println("You gave me an incorrect file name!");
            System.exit(0);
        }
        
        
        //setup the log file writing
        PrintWriter seqWriter = new PrintWriter("seqnum.log", "UTF-8");
        PrintWriter ackWriter = new PrintWriter("ack.log", "UTF-8");
        
        //initialize udp connection
        DatagramSocket udpSocketOut = new DatagramSocket();
        InetAddress IPAddress = InetAddress.getByName(emulatorAddress);
        
        DatagramSocket udpSocketIn = new DatagramSocket(ackPort);
        
		//runs listener on a seperate thread
		Multithread listener = new Multithread(udpSocketIn, ackWriter);
        
        //packet sending/receiving loop
        timeOut = System.currentTimeMillis(); //adjust timer quickly for first run
        while(true){//indefinite while loop

            //First check if window is full
            //System.out.println("cur read length is "+curRead+" out of "+fileSize);
            if(curRead<(int)fileSize){//determine if we even need to send anything else
                            
				if(windowCheck(base, N,(seq+1)%32)){
                    //window is not full, safe to send packet
                    //first determine sequence number
                    seq= (seq+1)%32;
                    //grab our data
                    //System.out.println("cur seq is "+seq);
                    packets[seq] = fileReader(file, fin, curRead, (int)fileSize);
                    curRead+=maxSize; //adjust read buffer number
                    
                    //send our packet
                    sendPacket(seq, packets[seq], IPAddress, udpSocketOut, dataPort);
                    
                    seqWriter.println(seq);
                    
                }
            } else {
                //check if EOT was sent
				
                int finalSpot = (packetsSize-1) % 32;
                if (base==finalSpot){//only send it after receiving all acks (easier to manage this way)
					//System.out.println("EOT check");                
					if(eotSent==false){
                    	//then send one!
                    	sendEOT((base+1)%32, IPAddress, udpSocketOut, dataPort);
                    	eotSent=true;
						//System.out.println("EOT sent now");
                    	seqWriter.println(seq);
                	}
                }
                
            }
            //check for timeouts
			if(eotSent==false){
            	if(timeOut+200<System.currentTimeMillis()){
                	//send the packet at base again
					//System.out.println("Got a time out");
                	sendPacket((base+1)%32, packets[(base+1)%32], IPAddress, udpSocketOut, dataPort);
                	timeOut= System.currentTimeMillis();
                	seqWriter.println(seq);
            	}
			}
            
            
            
            if(eotReceived==true){
            	break;
            }
            
            
        }
        
        udpSocketOut.close();//close up shop
        udpSocketIn.close();
        
        seqWriter.close();
        ackWriter.close();
        
        //closing our file reader as well
        try{
            if(fin !=null) {
                fin.close();
            }
        } catch (IOException e){
            System.out.println("Error closing the file stream: "+e);
        }
    }
    
    //This file reader does in fact keep track of position in file since it is
    //a stream. Very handy for this as we just take 500 or less bytes at a time
    //to send
    public static String fileReader (File file, FileInputStream fin, int curRead, int totalRead) {
        byte content[] = null;
		//we dont want extra bytes in our byte array because they result in corrupted end files
		//only send exactly what we need and no more
		if(curRead+maxSize<=totalRead){
			content =new byte[maxSize];
		} else {
			int size = totalRead-curRead;
			content= new byte[size];
		}
        try {
            fin.read(content);
        } catch (IOException e){
            System.out.println("Read error: "+e);
            System.exit(0);//attempt to deal with this gracefully instead of just exiting
        }
        
        String s = new String(content);
        //System.out.println("Content: "+s);
        return s;
        
    }
    
    
    static public void sendPacket(int seq, String data, InetAddress address, DatagramSocket socket, int sendPort){
        try {
            //System.out.println("Sending packet "+seq);
            packet newPacket = packet.createPacket(seq,data); //create our packet
            //convert our data to a byte array
            byte byteData[] = newPacket.getUDPdata();
            
            //send it
            DatagramPacket sendPacket = new DatagramPacket(byteData, byteData.length, address, sendPort);
            socket.send(sendPacket);
            
        } catch (Exception e){
            System.out.println("Error sending packet "+e);
        }
        
    }
    
    static public void sendEOT(int seq, InetAddress address, DatagramSocket socket, int sendPort){
        try {
			//System.out.println("Sending an EOT: "+seq);
            packet newPacket = packet.createEOT(seq); //create our packet
            //convert our data to a byte array
            byte byteData[] = newPacket.getUDPdata();
            
            //send it
            DatagramPacket sendPacket = new DatagramPacket(byteData, byteData.length, address, sendPort);
            socket.send(sendPacket);
            //System.out.println("EOT sent");
        } catch (Exception e){
            System.out.println("Error sending packet "+e);
        }
        
    }
    


	static class Multithread implements Runnable {

		private Thread curThread;
		private DatagramSocket socket;
		private PrintWriter writer;

		Multithread(DatagramSocket socket, PrintWriter writer) {
			this.curThread = new Thread(this, "Port Listening Thread");
			//System.out.println("listening thread was created: "+curThread);
			this.socket=socket;
			this.writer=writer;
			curThread.start();
		}

		public void run() {
            //System.out.println("Opening listener");
            while(eotReceived==false){
            	byte[] receiveData = new byte[512];
            	DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
				try {
					this.socket.receive(receivePacket);
					packet lPacket=null;
					lPacket = lPacket.parseUDPdata(receiveData);
					if(lPacket.getType()==0){
						setBase(lPacket.getSeqNum());
					} else if(lPacket.getType()==2){
						eotReceived=true;
					}
					this.writer.println(lPacket.getSeqNum());
				} catch (Exception e) {
					System.out.println("Listener exception: "+e);
				}
			}
		}

	}

	public static void setBase(int seq) {
		//System.out.println("Setting the base to: "+seq);
		base=seq;
	}
    
    public static boolean windowCheck(int base, int N, int seq) {
        //quick function to deal with the window wrapping since seq
        //is modulo 32
        if(base+N >=32){
            int dif = (base+N) %32;
            if(seq>=base && seq<32){
                return true;
            } else if (seq<base && seq<=dif){
                return true;
            } else {
                return false;
            }
        } else {
            if(seq<base+N){
                return true;
            } else {
                return false;
            }
        }
    }
    
}



