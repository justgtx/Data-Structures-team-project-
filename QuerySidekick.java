/*
 * QuerySidekick - Autocomplete Query Suggestion System
 * 
 * DESIGN OVERVIEW:
 * This implementation uses a Radix Trie (compressed trie) combined with sorted arrays
 * for efficient prefix-based query suggestions.
 * 
 * KEY OPTIMIZATIONS:
 * 1. Radix Trie - Compresses single-child paths to reduce node count
 * 2. Polymorphic Nodes - LeafNode (0 children), ChainNode (1 child), BranchNode (2+ children)
 *    This minimizes memory by only allocating what's needed per node
 * 3. Short[] IDs - Store query IDs (indices) instead of String references to save memory
 * 4. Sorted Arrays - Enable O(log n) binary search for prefix matching
 * 5. Pre-computed Cache - Top 5 suggestions cached at each trie node for O(1) lookup
 * 
 * SCORING FORMULA:
 * score = frequency^FREQUENCY_EXPONENT / query_length
 * Higher frequency and shorter length = higher score
 * 
 * TIME COMPLEXITY:
 * - processOldQueries: O(n * m) where n = queries, m = avg query length
 * - guess: O(prefix_length) - just trie traversal
 * - feedback: O(1)
 * 
 * SPACE COMPLEXITY: O(unique_queries * avg_length) for strings + O(nodes) for trie
 */

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
	
	// Root of the radix trie
	private RadixNode root;
	
	// Current prefix being typed by the user (accumulates across guess() calls)
	private String currentPrefix;
	
	// Exponent for frequency in scoring formula (1.0 = linear)
	private static final double FREQUENCY_EXPONENT = 1.0;
	
	// Query pool - sorted array of all unique queries (persists for ID-to-string lookups)
	private String[] queryPool;
	
	// Parallel array of frequencies (only used during cache building, then freed)
	private short[] queryFrequencies;
	
	// Reusable temporary arrays for finding top 5 (avoids object creation during cache build)
	private final short[] tempTop5Ids = new short[5];
	private final double[] tempScores = new double[5];
	private final int[] tempFreqs = new int[5];
	
	/*
	 * ==================== RADIX TRIE NODE CLASSES ====================
	 * 
	 * We use polymorphic nodes to minimize memory:
	 * - LeafNode: No children (terminal node or path compression endpoint)
	 * - ChainNode: Exactly 1 child (most common case in radix trie)
	 * - BranchNode: 2+ children (branching point)
	 * 
	 * Each node stores topSuggestionIds - the pre-computed top 5 query IDs for 
	 * the prefix leading to this node.
	 */
	
	/**
	 * Abstract base class for all radix trie nodes.
	 * Stores cached top suggestion IDs and defines child navigation interface.
	 */
	private abstract class RadixNode {
		// Cached top 5 query IDs for the prefix ending at this node
		// Uses short[] instead of String[] to save memory (2 bytes vs 8 bytes per entry)
		short[] topSuggestionIds;
		
		/**
		 * Find the edge starting with the given character.
		 * @param ch First character of the edge to find
		 * @return The RadixEdge if found, null otherwise
		 */
		abstract RadixEdge findChild(char ch);
		
		/**
		 * Add a new child edge. May return an upgraded node type if needed.
		 * @param ch First character of the new edge
		 * @param edge The new edge to add
		 * @return This node (possibly upgraded to a different type)
		 */
		abstract RadixNode addChildAndGetNode(char ch, RadixEdge edge);
		
		/**
		 * Copy cached data when upgrading node type.
		 */
		void copyDataTo(RadixNode other) {
			other.topSuggestionIds = this.topSuggestionIds;
		}
	}
	
	/**
	 * Leaf node with no children.
	 * When a child is added, upgrades to ChainNode.
	 */
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
	
	/**
	 * Node with exactly one child.
	 * Most memory-efficient for single-path sections of the trie.
	 * When a second child is added, upgrades to BranchNode.
	 */
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
	
	/**
	 * Node with 2 or more children.
	 * Uses parallel arrays for memory efficiency.
	 */
	private class BranchNode extends RadixNode {
		char[] childChars;     // First characters of each child edge
		RadixEdge[] childEdges; // Child edges (parallel array)
		
		@Override RadixEdge findChild(char ch) {
			// Linear search through children (typically small number)
			for (int i = 0; i < childChars.length; i++)
				if (childChars[i] == ch) return childEdges[i];
			return null;
		}
		
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
	
	/**
	 * Edge in the radix trie, connecting a parent node to a child node.
	 * The label contains the compressed path (may be multiple characters).
	 */
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
	
	/*
	 * ==================== CONSTRUCTOR ====================
	 */
	
	/**
	 * Initialize QuerySidekick with an empty radix trie.
	 */
	public QuerySidekick() {
		root = new LeafNode();
		rootEdge = new RadixEdge("", root);
		currentPrefix = "";
	}
	
	/*
	 * ==================== TRIE INSERTION METHODS ====================
	 */
	
	/**
	 * Insert a query into the frequency map and radix trie.
	 * Normalizes the query to lowercase.
	 * 
	 * @param query The query string to insert
	 * @param freqMap Map tracking query frequencies
	 */
	private void insertQuery(String query, Map<String, Integer> freqMap) {
		if (query == null || query.isEmpty()) return;
		query = query.toLowerCase();
		freqMap.put(query, freqMap.getOrDefault(query, 0) + 1);
		insertRadix(rootEdge, query, 0);
		root = rootEdge.node;
	}
	
	/**
	 * Recursively insert a query into the radix trie.
	 * Handles three cases:
	 * 1. No matching edge - create new edge with remaining query
	 * 2. Full edge match - recurse to child
	 * 3. Partial match - split edge and create branch
	 * 
	 * @param parentEdge The edge leading to the current node
	 * @param query The full query being inserted
	 * @param depth Current position in the query string
	 */
	private void insertRadix(RadixEdge parentEdge, String query, int depth) {
		RadixNode node = parentEdge.node;
		
		// Base case: reached end of query
		if (depth == query.length()) {
			return;
		}
		
		char firstChar = query.charAt(depth);
		RadixEdge edge = node.findChild(firstChar);
		
		// Case 1: No child with this character - create new edge
		if (edge == null) {
			LeafNode newNode = new LeafNode();
			// intern() the label to save memory via string deduplication
			RadixEdge newEdge = new RadixEdge(query.substring(depth).intern(), newNode);
			RadixNode upgraded = node.addChildAndGetNode(firstChar, newEdge);
			if (upgraded != node) parentEdge.node = upgraded;
			return;
		}
		
		// Found a matching edge - check how much of the label matches
		String edgeLabel = edge.label;
		int matchLen = 0;
		int maxLen = Math.min(edgeLabel.length(), query.length() - depth);
		while (matchLen < maxLen && edgeLabel.charAt(matchLen) == query.charAt(depth + matchLen)) {
			matchLen++;
		}
		
		// Case 2: Full edge match - recurse to child
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
	
	/*
	 * ==================== SORTED ARRAY BUILDING ====================
	 */
	
	/**
	 * Build sorted arrays from the frequency map.
	 * Query IDs are simply their index in the sorted array.
	 * This eliminates the need for a separate ID mapping.
	 * 
	 * @param freqMap Map of queries to their frequencies
	 */
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
	
	/*
	 * ==================== TOP-5 FINDING METHODS ====================
	 */
	
	/**
	 * Binary search to find the first query >= prefix in the sorted array.
	 * This is the starting point for prefix matching.
	 * 
	 * @param prefix The prefix to search for
	 * @return Index of first query >= prefix
	 */
	private int findChunkStart(String prefix) {
		int lo = 0, hi = queryPool.length;
		while (lo < hi) {
			int mid = (lo + hi) / 2;
			if (queryPool[mid].compareTo(prefix) < 0) lo = mid + 1;
			else hi = mid;
		}
		return lo;
	}
	
	/**
	 * Check if a query string starts with the given prefix.
	 * Manual implementation for performance (avoids String.startsWith overhead).
	 * 
	 * @param query The query to check
	 * @param prefix The prefix to match
	 * @return true if query starts with prefix
	 */
	private boolean startsWith(String query, String prefix) {
		if (query.length() < prefix.length()) return false;
		for (int i = 0; i < prefix.length(); i++)
			if (query.charAt(i) != prefix.charAt(i)) return false;
		return true;
	}
	
	/**
	 * Find the top 5 query IDs for a given prefix using binary search + scan.
	 * Results are stored in tempTop5Ids, tempScores, tempFreqs (no object creation).
	 * 
	 * Algorithm:
	 * 1. Binary search to find first matching query
	 * 2. Linear scan through matches (array is sorted, so matches are contiguous)
	 * 3. Maintain top 5 by score using insertion sort
	 * 
	 * @param prefix The prefix to find suggestions for
	 */
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
	
	/**
	 * Insert a query into the top 5 if it qualifies.
	 * Maintains sorted order by: score (desc), frequency (desc), alphabetical (asc).
	 * 
	 * @param id Query ID (index in queryPool)
	 * @param query The query string
	 * @param score Computed score
	 * @param freq Query frequency
	 */
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
	
	/*
	 * ==================== CACHE BUILDING ====================
	 */
	
	/**
	 * Build the suggestion cache for all nodes in the trie.
	 * Each node stores the top 5 query IDs for its prefix.
	 */
	private void buildCache() {
		buildCacheRecursive(root, "");
	}
	
	/**
	 * Recursively build cache for a node and its descendants.
	 * 
	 * @param node Current node
	 * @param pathSoFar The prefix string leading to this node
	 */
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
	
	/*
	 * ==================== PUBLIC API METHODS ====================
	 */
	
	/**
	 * Process the old queries file to build the suggestion system.
	 * This is the initialization/preprocessing step.
	 * 
	 * Steps:
	 * 1. Read all queries and count frequencies
	 * 2. Build radix trie structure
	 * 3. Build sorted arrays for binary search
	 * 4. Pre-compute top 5 cache for each trie node
	 * 5. Free temporary structures and trigger GC
	 * 
	 * @param oldQueryFile Path to the file containing historical queries
	 */
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
	
	/**
	 * Navigate the radix trie to find cached top IDs for a prefix.
	 * Returns null if prefix doesn't match any path in the trie.
	 * 
	 * @param prefix The prefix to look up
	 * @return Array of top query IDs, or null if prefix not found
	 */
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
	
	/**
	 * Generate 5 query suggestions based on the current character.
	 * Called once for each character the user types.
	 * 
	 * @param currChar The character just typed
	 * @param currCharPosition Position in the query (0 = first character)
	 * @return Array of 5 suggestions (may contain nulls)
	 */
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
	
	/**
	 * Receive feedback about the guess result.
	 * Called after each query is completed (correctly guessed or fully typed).
	 * 
	 * @param isCorrectGuess Whether our guess was correct
	 * @param correctQuery The actual query (null if still typing)
	 */
	public void feedback(boolean isCorrectGuess, String correctQuery) {
		// Reset prefix for next query
		if (correctQuery != null) {
			currentPrefix = "";
		}
	}
}
