/*
Authors (group members):
    Edwin Cruz Cordova, Jackson Neering, Basil Salem, Gio Williams
Email addresses of group members:
    jneering2023@my.fit.edu, bsalem2024@my.fit.edu, gwilliams2024@my.fit.edu, ecruz2024@my.fit.edu
Group name: A Boogie No Hoodie
Course: CSE2010
Section: 3/4
Description of the overall algorithm:
    'Search engine' type query search implemented using a Radix Trie DS. Optimized with different Node types.
    The algorithm preprocesses queries into a top 5 suggestion on each node, which can be quickly navigated to.
*/
//import statements.
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class QuerySidekick
{
	// Output array for the 5 guesses returned by guess()
	String[] guesses = new String[5];
	
	// radix trie root
	private RadixNode root;
	
	// Current prefix being typed by the user (accumulates across guess() calls)
	private String currentPrefix;
	
	// Exponent for frequency in scoring formula (1.0 = linear)
    //one was the most stable exponent
	private static final double FREQUENCY_EXPONENT = 1.0;
	
	// Query pool - sorted array of all unique queries
	private String[] queryPool;
	
	// Parallel array of frequencies, only used during cache building, then freed
	private short[] queryFrequencies;
	
	// Reusable temporary arrays for finding top 5, used to avoid object creation during cache build
	private final short[] tempTop5Ids = new short[5];
	private final double[] tempScores = new double[5];
	private final int[] tempFreqs = new int[5];

    //Radix Trie Node class:
    //used three different nodes to reduce memory usage.
	private abstract class RadixNode {
		// Cached top 5 query IDs for the prefix ending at this node
		// Uses short[] instead of String[] to save memory (2 bytes vs 8 bytes per entry)
		short[] topSuggestionIds;

        //finds the edge starting at a given character, and returns it if found, null if not
		abstract RadixEdge findChild(char ch);

        //adds a bew child edge, may return an upgraded node if needed.
		abstract RadixNode addChildAndGetNode(char ch, RadixEdge edge);

		void copyDataTo(RadixNode other) {
			other.topSuggestionIds = this.topSuggestionIds;
		}
	}

    //leaf node with no children, when a child is added, it is upgraded to a 'chain node'
	private class LeafNode extends RadixNode {
		@Override RadixEdge findChild(char ch) { return null; }
		
		@Override RadixNode addChildAndGetNode(char ch, RadixEdge edge) {
			// Upgrade to ChainNode when first child is added
			ChainNode n = new ChainNode(); 
			copyDataTo(n);
			n.childChar = ch; 
			n.childEdge = edge; 
			return n;
		}
	}
    //node with exactly one child, almost like a linked list.
        //this implementation is most memory efficient for single-path selections
	private class ChainNode extends RadixNode {
		char childChar;      // First character of the single child edge
		RadixEdge childEdge; // The single child edge
		
		@Override RadixEdge findChild(char ch) { 
			return (childChar == ch) ? childEdge : null; 
		}
		
		@Override RadixNode addChildAndGetNode(char ch, RadixEdge edge) {
			// Upgrade to BranchNode when second child is added
			BranchNode n = new BranchNode(); 
			copyDataTo(n);
			n.childChars = new char[]{childChar, ch};
			n.childEdges = new RadixEdge[]{childEdge, edge}; 
			return n;
		}
	}

    //Node type with 2+ nodes associated with it.
	private class BranchNode extends RadixNode {
        //more memory efficient than a hashmap
		char[] childChars;     // First characters of each child edge
		RadixEdge[] childEdges; // Child edges (parallel array)
		
		@Override RadixEdge findChild(char ch) {
			// Linear search through children (typically small number)
			for (int i = 0; i < childChars.length; i++)
				if (childChars[i] == ch) return childEdges[i];
			return null;
		}
		//increases array size when adding a new character
		@Override RadixNode addChildAndGetNode(char ch, RadixEdge edge) {
			// Expand arrays to add new child
			int len = childChars.length;
			childChars = Arrays.copyOf(childChars, len + 1);
			childEdges = Arrays.copyOf(childEdges, len + 1);
			childChars[len] = ch; 
			childEdges[len] = edge; 
			return this;
		}
	}

    //Radix edge that connects the parent to the child nodes.
	private class RadixEdge {
		String label;    // The edge label (compressed path)
		RadixNode node;  // The child node this edge points to
		
		RadixEdge(String label, RadixNode node) { 
			this.label = label; 
			this.node = node; 
		}
	}
	
	// Special edge for the root (empty label)
	private RadixEdge rootEdge;
	
	//init for 'QuerySideKick'
	public QuerySidekick() {
		root = new LeafNode(); //inits the root node
		rootEdge = new RadixEdge("", root);
		currentPrefix = "";
	}

    //inserts a query into a radix trie and freq map.
	private void insertQuery(String query, Map<String, Integer> freqMap) {
		if (query == null || query.isEmpty()) return;
        //normalizes all cahracters to lowercase
		query = query.toLowerCase();
		freqMap.put(query, freqMap.getOrDefault(query, 0) + 1);
		insertRadix(rootEdge, query, 0);
		root = rootEdge.node;
	}

    //recursively insert a query into the trie. Handles three cases:
    //1. No matching edge
    //2. Full edge match
    //3. Partial match
	private void insertRadix(RadixEdge parentEdge, String query, int depth) {
		RadixNode node = parentEdge.node;
		
		// Base case: reached end of query
		if (depth == query.length()) {
			return;
		}
		
		char firstChar = query.charAt(depth);
		RadixEdge edge = node.findChild(firstChar);
		
		// Case 1: No child with this character, create new edge
		if (edge == null) {
			LeafNode newNode = new LeafNode();
			// intern() the label to save memory via string deduplication
			RadixEdge newEdge = new RadixEdge(query.substring(depth).intern(), newNode);
			RadixNode upgraded = node.addChildAndGetNode(firstChar, newEdge);
			if (upgraded != node) parentEdge.node = upgraded;
			return;
		}
		
		// Found a matching edge, check how much of the label matches
		String edgeLabel = edge.label;
		int matchLen = 0;
		int maxLen = Math.min(edgeLabel.length(), query.length() - depth);
		while (matchLen < maxLen && edgeLabel.charAt(matchLen) == query.charAt(depth + matchLen)) {
			matchLen++;
		}
		
		// Case 2: Full edge match, recurse to child
		if (matchLen == edgeLabel.length()) {
			insertRadix(edge, query, depth + matchLen);
			return;
		}
		
		// Case 3: Partial match - need to split the edge
		String commonPrefix = edgeLabel.substring(0, matchLen).intern();
		String oldSuffix = edgeLabel.substring(matchLen).intern();
		String newSuffix = (depth + matchLen < query.length()) ? 
			query.substring(depth + matchLen).intern() : "";
		
		// Create a split node at the divergence point
		RadixNode splitNode = new LeafNode();
		edge.label = commonPrefix;
		RadixNode oldTarget = edge.node;
		edge.node = splitNode;
		
		// Re-attach the old subtree under the split node
		splitNode = splitNode.addChildAndGetNode(oldSuffix.charAt(0), 
			new RadixEdge(oldSuffix, oldTarget));
		edge.node = splitNode;
		
		// Add the new suffix if non-empty
		if (!newSuffix.isEmpty()) {
			LeafNode newNode = new LeafNode();
			RadixNode upgraded = splitNode.addChildAndGetNode(newSuffix.charAt(0), 
				new RadixEdge(newSuffix, newNode));
			if (upgraded != splitNode) edge.node = upgraded;
		}
	}

    //builds sorted arrays from the freq map.
	private void buildSortedArrays(Map<String, Integer> freqMap) {
		int n = freqMap.size();
		
		// Extract and sort keys alphabetically
		String[] keys = freqMap.keySet().toArray(new String[0]);
		Arrays.sort(keys);
		
		// Build parallel arrays
		queryPool = new String[n];
		queryFrequencies = new short[n];
		for (int i = 0; i < n; i++) {
			queryPool[i] = keys[i].intern();
			// Cap frequency at Short.MAX_VALUE to save memory
			queryFrequencies[i] = (short) Math.min(freqMap.get(keys[i]), Short.MAX_VALUE);
		}
		keys = null; // Help garbage collection
	}
    //Binary search method that finds the first query larger than the prefix
	private int findChunkStart(String prefix) {
		int lo = 0, hi = queryPool.length;
		while (lo < hi) {
			int mid = (lo + hi) / 2;
			if (queryPool[mid].compareTo(prefix) < 0) lo = mid + 1;
			else hi = mid;
		}
		return lo;
	}
    //checks if query starts with a given prefix.
    //avoids the overhead that comes with 'String.startsWith'
	private boolean startsWith(String query, String prefix) {
		if (query.length() < prefix.length()) return false;
		for (int i = 0; i < prefix.length(); i++)
			if (query.charAt(i) != prefix.charAt(i)) return false;
		return true;
	}

    //Finds the top 5 query IDs for a prefix.
    //Results are stored in the TempTop5IDs, tempScores, tempFreqs arrays.
	private void findTop5IdsForPrefix(String prefix) {
		// Reset temporary arrays
		for (int i = 0; i < 5; i++) {
			tempTop5Ids[i] = -1;
			tempScores[i] = Double.NEGATIVE_INFINITY;
			tempFreqs[i] = 0;
		}
		
		// Find first query >= prefix
		int start = findChunkStart(prefix);
		
		// Scan forward through all queries matching prefix
		for (int i = start; i < queryPool.length; i++) {
			if (!startsWith(queryPool[i], prefix)) break; // No more matches
			
			String query = queryPool[i];
			int freq = queryFrequencies[i];
			// Scoring: higher frequency and shorter length = better
			double score = Math.pow(freq, FREQUENCY_EXPONENT) / query.length();
			insertIntoTop5((short)i, query, score, freq);
		}
	}

    //inserts the queries into the top 5
    //maintains their sorted order.
	private void insertIntoTop5(short id, String query, double score, int freq) {
		// Find insertion position
		int pos = -1;
		for (int i = 0; i < 5; i++) {
			if (tempTop5Ids[i] < 0) { pos = i; break; } // Empty slot
			if (score > tempScores[i]) { pos = i; break; } // Higher score
			if (score == tempScores[i]) {
				if (freq > tempFreqs[i]) { pos = i; break; } // Higher frequency (tiebreaker 1)
				if (freq == tempFreqs[i] && query.compareTo(queryPool[tempTop5Ids[i]]) < 0) { 
					pos = i; break; // Alphabetically first (tiebreaker 2)
				}
			}
		}
		
		if (pos == -1) return; // Doesn't qualify for top 5
		
		// Shift lower-ranked entries down
		for (int i = 4; i > pos; i--) {
			tempTop5Ids[i] = tempTop5Ids[i-1];
			tempScores[i] = tempScores[i-1];
			tempFreqs[i] = tempFreqs[i-1];
		}
		
		// Insert at position
		tempTop5Ids[pos] = id;
		tempScores[pos] = score;
		tempFreqs[pos] = freq;
	}
	

    //recursively build the cache for all the nodes in the trie.
	private void buildCache() {
		buildCacheRecursive(root, "");
	}

    //builds the cache for a node and its descendants.
	private void buildCacheRecursive(RadixNode node, String pathSoFar) {
		if (node == null) return;
		
		// Find top 5 IDs for the prefix ending at this node
		findTop5IdsForPrefix(pathSoFar);
		
		// Count valid entries
		int count = 0;
		for (int i = 0; i < 5; i++) {
			if (tempTop5Ids[i] >= 0) count++;
		}
		
		// Store as short[] (memory efficient)
		if (count > 0) {
			node.topSuggestionIds = new short[count];
			for (int i = 0; i < count; i++) {
				node.topSuggestionIds[i] = tempTop5Ids[i];
			}
		}
		
		// Recurse to children
		if (node instanceof ChainNode) {
			ChainNode cn = (ChainNode) node;
			buildCacheRecursive(cn.childEdge.node, pathSoFar + cn.childEdge.label);
		} else if (node instanceof BranchNode) {
			BranchNode bn = (BranchNode) node;
			for (RadixEdge edge : bn.childEdges) {
				buildCacheRecursive(edge.node, pathSoFar + edge.label);
			}
		}
	}

    //Process the old query files to build the suggestion system.
    //clears unused memory via System.gc
	public void processOldQueries(String oldQueryFile) {
		Map<String, Integer> freqMap = new HashMap<>();
		try (BufferedReader reader = new BufferedReader(new FileReader(oldQueryFile))) {
			String line;
			while ((line = reader.readLine()) != null) {
				// Normalize whitespace
				line = line.replaceAll("\\s+", " ").trim();
				if (!line.isEmpty()) {
					insertQuery(line, freqMap);
				}
			}
			
			// Build sorted arrays (queryPool persists for lookups)
			buildSortedArrays(freqMap);
			freqMap = null; // Help GC
			
			// Build cache with short[] IDs
			buildCache();
			
			// Free queryFrequencies (only needed during cache building)
			queryFrequencies = null;
			
			// Force garbage collection to reduce peak memory measurement
			System.gc();
			
		} catch (IOException e) {
			System.err.println("Error: " + e.getMessage());
		}
	}

    //traverse the trie to find the top cached ids.
    //returns null if the prefix doesn't match any path.
	private short[] findTopIds(String prefix) {
		RadixNode current = root;
		int matched = 0;
		
		while (matched < prefix.length()) {
			// Find edge starting with next unmatched character
			RadixEdge edge = current.findChild(prefix.charAt(matched));
			if (edge == null) return null; // No matching edge
			
			String label = edge.label;
			int toCheck = Math.min(label.length(), prefix.length() - matched);
			
			// Verify the edge label matches the prefix
			for (int i = 0; i < toCheck; i++) {
				if (label.charAt(i) != prefix.charAt(matched + i)) {
					return null; // Mismatch
				}
			}
			
			matched += toCheck;
			current = edge.node;
		}
		
		return current.topSuggestionIds;
	}
    //Generate 5 queries based on the current inputed character.
    //called each time the user types a character
	public String[] guess(char currChar, int currCharPosition) {
		// Normalize to lowercase
		currChar = Character.toLowerCase(currChar);
		
		// Build/extend the current prefix
		currentPrefix = (currCharPosition == 0) ? "" + currChar : currentPrefix + currChar;
		
		// Look up cached suggestions
		short[] topIds = findTopIds(currentPrefix);
		
		// Convert IDs to strings using queryPool (O(1) per lookup)
		for (int i = 0; i < 5; i++) {
			if (topIds != null && i < topIds.length && topIds[i] >= 0) {
				guesses[i] = queryPool[topIds[i]];
			} else {
				guesses[i] = null;
			}
		}
		
		return guesses;
	}

    //Received feedback about the guess result and resets prefix if needed.
	public void feedback(boolean isCorrectGuess, String correctQuery) {
		// Reset prefix for next query
		if (correctQuery != null) {
			currentPrefix = "";
		}
	}
}
