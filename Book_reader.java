import java.io.*;
public class Book_reader{
    public static void main(String args[] ){
    if( args.length != 1 ){
        System.out.println("missing input");
        System.exit(1);
    }
    try{
        BufferedReader reader = new BufferedReader( new FileReader( args[0] ) );
        String line;
        while( (line = reader.readLine() ) != null ){
            System.out.println( line );
        }
        reader.close();
    } catch( IOException e ){
        System.out.println("IO Error: " + e.getMessage() );
    }
    }}