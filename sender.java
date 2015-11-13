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

    private static int maxSize =50;
    
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
        int base =0;
        int seq=-1;
        
        long timeOut =0;
        boolean eotSent = false;
        boolean eotReceived = false;
        
        
        //open up the file
        File file = new File(fileName);
        FileInputStream fin =null;
        
        //as an easy way to monitor whats been read I just track the length so far
        double fileSize =(double)file.length();
        int curRead =0;
        
        int packetsSize =(int)Math.ceil(fileSize/maxSize);
        String packets[]=new String[32]; //just hard code to 32
        
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
        
        packet lPacket = null;
        
        //packet sending/receiving loop
        timeOut = System.currentTimeMillis(); //adjust timer quickly for first run
        while(true){//indefinite while loop
            //First check if window is full
            System.out.println("cur read length is "+curRead);
            if(curRead<fileSize){//determine if we even need to send anything else
                if(windowCheck(base, N,(seq+1)%32)){
                    //window is not full, safe to send packet
                    //first determine sequence number
                    seq= (seq+1)%32;
                    //grab our data
                    System.out.println("cur seq is "+seq);
                    packets[seq] = fileReader(file, fin);
                    curRead+=maxSize; //adjust read buffer number
                    
                    //send our packet
                    sendPacket(seq, packets[seq], IPAddress, udpSocketOut, dataPort);
                    
                    seqWriter.println(seq);
                    
                }
            } else {
                //check if EOT was sent
                int finalSpot = packetsSize % 32;
                if (base==finalSpot){//only send it after receiving all acks (easier to manage this way)
                if(eotSent==false){
                    //then send one!
                    sendEOT((seq+1)%32, IPAddress, udpSocketOut, dataPort);
                    eotSent=true;
                    seqWriter.println(seq);
                }
                }
                
            }
            //check for timeouts
            if(timeOut+200<System.currentTimeMillis()){
                //send the packet at base again
                sendPacket(base, packets[base], IPAddress, udpSocketOut, dataPort);
                timeOut= System.currentTimeMillis();
                seqWriter.println(seq);
            }
            
            
            lPacket = listenPacket(udpSocketIn);
            if(lPacket!=null){
                System.out.println("Received pack from listener");
                //woo we got something back!
                //lets get to work on it
                //first check packet type
                if(lPacket.getType()==0) { //its an ACK!
                    //check its seq num
                    int thisSeq = lPacket.getSeqNum();
                    //check if its within the proper window
                    if(windowCheck(base, N, thisSeq)){
                        base= thisSeq;//if so then its our new base
                    }
                    ackWriter.println(thisSeq);
                } else if(lPacket.getType()==2){//EOT
                    eotReceived=true;
                }
            }
            
            if(eotReceived==true){
                //check if we have acks for all!
                int finalSpot = packetsSize % 32;
                if (base==finalSpot){
                    //a surprisingly easy solution to this. We can pre calculate what
                    //the final seq number will be based on file size which we did at the
                    //start. Even though we have wrapping seq numbers, the EOT wont be sent
                    //until we are on our final wrap meaning the overlaps dont 
                    //actually matter
                    break;
                }
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
    public static String fileReader (File file, FileInputStream fin) {
        byte content[] = new byte[maxSize];
        try {
            fin.read(content);
        } catch (IOException e){
            System.out.println("Read error: "+e);
            System.exit(0);//attempt to deal with this gracefully instead of just exiting
        }
        
        String s = new String(content);
        System.out.println("Content: "+s);
        return s;
        
    }
    
    
    static public void sendPacket(int seq, String data, InetAddress address, DatagramSocket socket, int sendPort){
        try {
            System.out.println("Sending packet "+seq);
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
            packet newPacket = packet.createEOT(seq); //create our packet
            //convert our data to a byte array
            byte byteData[] = newPacket.getUDPdata();
            
            //send it
            DatagramPacket sendPacket = new DatagramPacket(byteData, byteData.length, address, sendPort);
            socket.send(sendPacket);
            
        } catch (Exception e){
            System.out.println("Error sending packet "+e);
        }
        
    }
    
    static public packet listenPacket(DatagramSocket socket){
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<packet> future = executor.submit(new Task(socket));
        
        packet newPacket = null;
        
        try {
            newPacket= future.get(50,TimeUnit.MILLISECONDS);
        
        } catch (Exception e){
            future.cancel(true);
            //System.out.println("Terminated: "+e);
        }
        
        executor.shutdownNow();
        return newPacket;
    }
    
    static class Task implements Callable<packet> {
        private DatagramSocket socket;
        
        
        public Task(DatagramSocket givenSocket){
            this.socket=givenSocket;
        }
    
        @Override
        public packet call() throws Exception {
            System.out.println("Opening listener");
            
            byte[] receiveData = new byte[512];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            socket.receive(receivePacket);
            packet newPacket=null;
            try {
                newPacket = newPacket.parseUDPdata(receiveData);
            } catch (Exception e){
                //packet parsing exception
            }
            return newPacket;
        }
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



