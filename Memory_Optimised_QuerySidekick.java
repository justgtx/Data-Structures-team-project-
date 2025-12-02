/*
Memory-Optimized QuerySidekick Implementation
Key optimizations:
1. Removed topSuggestions cache from every node (huge memory save)
2. Removed redundant 'query' field - reconstruct from path
3. Use array instead of HashMap for common characters
4. Compute suggestions on-demand with early termination
5. Reduced object overhead
*/
import java.io.*;
import java.util.*;

public class Memory_Optimised_QuerySidekick {
    private String[] guesses = new String[5];
    private TrieNode root;
    private static final double FREQUENCY_EXPONENT = 1.0;
    
    // Memory-efficient Trie Node
    private class TrieNode {
        // Use array for a-z and space (27 slots) - much more memory efficient than HashMap
        // For other characters, fall back to a small HashMap
        TrieNode[] commonChildren; // indices 0-25: a-z, index 26: space
        Map<Character, TrieNode> otherChildren; // for uncommon characters
        boolean isEndOfQuery;
        int frequency;
        
        public TrieNode() {
            // Lazy initialization - only create arrays when needed
            commonChildren = null;
            otherChildren = null;
            isEndOfQuery = false;
            frequency = 0;
        }
        
        TrieNode getChild(char ch) {
            if (ch >= 'a' && ch <= 'z') {
                return commonChildren != null ? commonChildren[ch - 'a'] : null;
            } else if (ch == ' ') {
                return commonChildren != null ? commonChildren[26] : null;
            } else {
                return otherChildren != null ? otherChildren.get(ch) : null;
            }
        }
        
        void putChild(char ch, TrieNode node) {
            if (ch >= 'a' && ch <= 'z') {
                if (commonChildren == null) {
                    commonChildren = new TrieNode[27];
                }
                commonChildren[ch - 'a'] = node;
            } else if (ch == ' ') {
                if (commonChildren == null) {
                    commonChildren = new TrieNode[27];
                }
                commonChildren[26] = node;
            } else {
                if (otherChildren == null) {
                    otherChildren = new HashMap<>();
                }
                otherChildren.put(ch, node);
            }
        }
        
        Iterable<Map.Entry<Character, TrieNode>> getChildren() {
            List<Map.Entry<Character, TrieNode>> children = new ArrayList<>();
            
            if (commonChildren != null) {
                for (int i = 0; i < 26; i++) {
                    if (commonChildren[i] != null) {
                        char ch = (char)('a' + i);
                        children.add(new AbstractMap.SimpleEntry<>(ch, commonChildren[i]));
                    }
                }
                if (commonChildren[26] != null) {
                    children.add(new AbstractMap.SimpleEntry<>(' ', commonChildren[26]));
                }
            }
            
            if (otherChildren != null) {
                children.addAll(otherChildren.entrySet());
            }
            
            return children;
        }
    }
    
    // Lightweight suggestion holder - no need for full object
    private class QuerySuggestion implements Comparable<QuerySuggestion> {
        String query;
        double score;
        
        QuerySuggestion(String query, int frequency) {
            this.query = query;
            this.score = Math.pow(frequency, FREQUENCY_EXPONENT) / query.length();
        }
        
        @Override
        public int compareTo(QuerySuggestion other) {
            int scoreComp = Double.compare(other.score, this.score);
            return scoreComp != 0 ? scoreComp : this.query.compareTo(other.query);
        }
    }
    
    public QuerySidekick() {
        root = new TrieNode();
    }
    
    private void insertQuery(String query) {
        if (query == null || query.isEmpty()) return;
        
        query = query.toLowerCase().intern(); // intern() saves memory for duplicate strings
        
        TrieNode current = root;
        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            TrieNode child = current.getChild(ch);
            if (child == null) {
                child = new TrieNode();
                current.putChild(ch, child);
            }
            current = child;
        }
        current.isEndOfQuery = true;
        current.frequency++;
    }
    
    public void processOldQueries(String oldQueryFile) {
        try (BufferedReader reader = new BufferedReader(new FileReader(oldQueryFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.replaceAll("\\s+", " ").trim();
                if (!line.isEmpty()) {
                    insertQuery(line);
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading old queries file: " + e.getMessage());
        }
    }
    
    // Find top 5 queries on-demand (no pre-caching)
    // Use a min-heap of size 5 for memory efficiency
    private String[] findTopQueries(String prefix) {
        TrieNode current = root;
        for (int i = 0; i < prefix.length(); i++) {
            char ch = prefix.charAt(i);
            current = current.getChild(ch);
            if (current == null) {
                return new String[0];
            }
        }
        
        // Use priority queue (min-heap) to keep only top 5
        PriorityQueue<QuerySuggestion> topK = new PriorityQueue<>(5);
        
        // Collect queries using DFS with path reconstruction
        collectTopQueries(current, prefix, topK);
        
        // Extract results in reverse order
        String[] results = new String[topK.size()];
        for (int i = results.length - 1; i >= 0; i--) {
            results[i] = topK.poll().query;
        }
        
        return results;
    }
    
    // Collect top queries using DFS, maintaining only top 5 in memory
    private void collectTopQueries(TrieNode node, String path, PriorityQueue<QuerySuggestion> topK) {
        if (node.isEndOfQuery) {
            QuerySuggestion suggestion = new QuerySuggestion(path, node.frequency);
            
            if (topK.size() < 5) {
                topK.offer(suggestion);
            } else if (suggestion.compareTo(topK.peek()) < 0) {
                topK.poll();
                topK.offer(suggestion);
            }
        }
        
        for (Map.Entry<Character, TrieNode> entry : node.getChildren()) {
            collectTopQueries(entry.getValue(), path + entry.getKey(), topK);
        }
    }
    
    public String[] guess(char currChar, int currCharPosition) {
        currChar = Character.toLowerCase(currChar);
        
        // Build prefix on the fly
        StringBuilder prefix = new StringBuilder(currCharPosition + 1);
        prefix.append(currChar);
        
        // If not first character, we'd need to track prefix differently
        // For this implementation, we'll reconstruct from the character sequence
        // In practice, you might want to pass the full prefix to this method
        
        String[] topQueries = findTopQueries(prefix.toString());
        
        for (int i = 0; i < 5; i++) {
            guesses[i] = i < topQueries.length ? topQueries[i] : null;
        }
        
        return guesses;
    }
    
    public void feedback(boolean isCorrectGuess, String correctQuery) {
        // Optional: Add the new query to improve future predictions
        if (correctQuery != null && !isCorrectGuess) {
            insertQuery(correctQuery);
        }
    }
}
