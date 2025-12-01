//arraylist of common words
import java.lang.reflect.Array;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
public class RadixTrie {

    private RadixNode root;

    class RadixNode {
        Map<String, RadixNode> children = new HashMap<>();
        boolean isEndOfWord = false;
        int frequency = 0;
    }
    public RadixTrie() {
        root = new RadixNode();
    }

    public void insert(String word){
        insertRecursive(root, word);
    }

    private void insertRecursive(RadixNode node, String word){
        if(word.isEmpty()){
            node.isEndOfWord = true;
            node.frequency++;
            return;
        }
        
        for(Map.Entry<String, RadixNode> entry : node.children.entrySet()){
            String edgeLabel = entry.getKey();
            RadixNode childNode = entry.getValue();

            int commonPrefixLength = getCommonPrefixLength(word, edgeLabel);

            //case 1: No common prefix
            if(commonPrefixLength == 0){
                continue;
            }
            if(commonPrefixLength == edgeLabel.length()){
                //case 2: Full edge match. The entire edge is a prefix of the word.
                String remainingWord = word.substring(commonPrefixLength);
                insertRecursive(childNode, remainingWord);
                return;
            }
            
            if(commonPrefixLength < edgeLabel.length()){
                //case 3: partial edge match

                //shared prefix between word and edgelabel
                String commonPrefix = edgeLabel.substring(0, commonPrefixLength);
                //what was left on the edge after the common prefix
                String remainingEdge = edgeLabel.substring(commonPrefix.length());
                //what was left of the word after the common prefix
                String remainingWord = word.substring(commonPrefixLength);

                //create the new intermediate node
                RadixNode intermediateNode = new RadixNode();
                //remove the old, long edge from the current node
                node.children.remove(edgeLabel);
                //add the original child as a child of the intermediate node
                node.children.put(commonPrefix, intermediateNode);
                //add the remaining edge to the original child node
                intermediateNode.children.put(remainingEdge, childNode);

                //add the new word's remainig part as another child.
                RadixNode newWordNode = new RadixNode();
                newWordNode.isEndOfWord = true;
                newWordNode.frequency = 1;
                intermediateNode.children.put(remainingWord, newWordNode);
                return;
            }
        }
        //case 1 cont: original node
        RadixNode newNode = new RadixNode();
        newNode.isEndOfWord = true;
        newNode.frequency = 1;
        node.children.put(word, newNode);   
    }
    private int getCommonPrefixLength(String s1, String s2){
        int len = 0;
        int minLen = Math.min(s1.length(), s2.length());
        for(int i = 0; i < minLen; i++){
            if(s1.charAt(i) == s2.charAt(i)){
                len++;
            }else{
                break;
            }
        }
        return len;
    }

//    public ArrayList<String> search(String prefix){
//
//    }

}

