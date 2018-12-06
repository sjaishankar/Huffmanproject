import java.util.PriorityQueue;

/**
 * Although this class has a history of several years,
 * it is starting from a blank-slate, new and clean implementation
 * as of Fall 2018.
 * <P>
 * Changes include relying solely on a tree for header information
 * and including debug and bits read/written information
 * 
 * @author Owen Astrachan
 */

public class HuffProcessor {

	public static final int BITS_PER_WORD = 8;
	public static final int BITS_PER_INT = 32;
	public static final int ALPH_SIZE = (1 << BITS_PER_WORD); 
	public static final int PSEUDO_EOF = ALPH_SIZE;
	public static final int HUFF_NUMBER = 0xface8200;
	public static final int HUFF_TREE  = HUFF_NUMBER | 1;

	private final int myDebugLevel;
	
	public static final int DEBUG_HIGH = 4;
	public static final int DEBUG_LOW = 1;
	
	public HuffProcessor() {
		this(0);
	}
	
	public HuffProcessor(int debug) {
		myDebugLevel = debug;
	}

	/**
	 * Compresses a file. Process must be reversible and loss-less.
	 *
	 * @param in
	 *            Buffered bit stream of the file to be compressed.
	 * @param out
	 *            Buffered bit stream writing to the output file.
	 */
	public void compress(BitInputStream in, BitOutputStream out){
		//creates frequency counts for each 8-bit char using readForCounts helper method
		int[] counts = readForCounts(in);
		
		//create Huffman Tree based on counts of each 8-bit char by determining each character's weight using makeTreeFromCounts helper method
		HuffNode root = makeTreeFromCounts(counts);
		
		//Creates an array of path-based encodings for each char from Huffman tree using makeCodingsFromTree helper method
		String[] codings = makeCodingsFromTree(root);
		
		//Write "magic" number for Huffman tree at beginning of compressed file
		out.writeBits(BITS_PER_INT, HUFF_TREE);
		writeHeader(root, out);
		
		//Reset file, read file again, and write the compressed bits for new file based on same weights
		in.reset();
		writeCompressedBits(codings, in, out);
		out.close();
	}
	
	private int[] readForCounts(BitInputStream in) {
		//create array of 257 elements to hold frequencies for each 8-bit char
		int[] counts = new int[ALPH_SIZE+1];
		
		//automatically set count for PSEUDO_EOF to 1 since it is not a real character
		counts[PSEUDO_EOF] = 1;
		
		
		while(true) {
			//creates a set of bits from file
			int bits = in.readBits(BITS_PER_WORD);
			
			//breaks out of while loop when end of file is reached
			if(bits == -1) break;
			
			//increment frequency of specific bits location in array
			counts[bits]++;
		}
		return counts;
	}
	
	private HuffNode makeTreeFromCounts(int[] counts) {
		//create priority queue of HuffNodes to sort based on weight and create Huffman tree based on these weights
		PriorityQueue<HuffNode> pq = new PriorityQueue<>();
		
		for(int i = 0; i < counts.length; i++) {
			if(counts[i] > 0) {
				//for characters that occurred at least once, places in Priority Queue
				pq.add(new HuffNode(i,counts[i],null,null));
			}
		}
		
		while(pq.size() > 1) {
			//creates two HuffNodes based on the least-weighted ones in Priority Queue
			HuffNode left = pq.remove();
			HuffNode right = pq.remove();
			//combines two least-weight HuffNodes into new HuffNode and adds back to pq
			HuffNode t = new HuffNode(0, left.myWeight+right.myWeight, left, right);
			pq.add(t);
		}
		
		//The only thing left in the priority queue after the while loop completes is one HuffTree
		HuffNode root = pq.remove();
		return root;
	}
	
	private String[] makeCodingsFromTree(HuffNode root) {
		//initializes array of path encodings based on Huffman tree for all 8-bit charatcers
		String[] coding = new String[ALPH_SIZE + 1];
		
		//calls helper method to create encodings based on recursive method
		codingHelper(root, "", coding);
		return coding;
	}
	
	private void codingHelper(HuffNode root, String path, String[] coding) {
		//if HuffNode is a leaf, then path gets the value stored at leaf and ends encoding for that specific character
		if(root.myLeft == null && root.myRight == null) {
			coding[root.myValue] = path;
			return;
		}
		
		//adds "0" to path if path is left
		codingHelper(root.myLeft, path + "0", coding);
		
		//adds "1" to path if path is right
		codingHelper(root.myRight, path + "1", coding);
	}
	
	private void writeHeader(HuffNode root, BitOutputStream out) {
		//if HuffNode is not a leaf, add 0 to traversal of tree
		if(root.myLeft != null || root.myRight != null) {
			out.writeBits(1, 0);
			writeHeader(root.myLeft, out);
			writeHeader(root.myRight, out);
		}
		
		//if HuffNode is a leaf, add "1" to traversal of tree, and add character to tree
		else {
			out.writeBits(1, 1);
			out.writeBits(BITS_PER_WORD + 1, root.myValue);
		}
	}
	
	private void writeCompressedBits(String[] codings, BitInputStream in, BitOutputStream out) {
		while(true) {
			//read bits for word in file
			int bits = in.readBits(BITS_PER_WORD);
			
			if(bits == -1) break;
			
			//store encoding for one specific set of bits in string
			String bitseq = codings[bits];
			
			if(bitseq != null) {
				//write encoded bit (via Huffman tree) to out
				out.writeBits(bitseq.length(), Integer.parseInt(bitseq, 2));
			}
		}
		
		//manually encode and write Huffman tree bits for PSEUDO_EOF
		String bitseq = codings[PSEUDO_EOF];
		out.writeBits(bitseq.length(), Integer.parseInt(bitseq, 2));
	}
	
	/**
	 * Decompresses a file. Output file must be identical bit-by-bit to the
	 * original.
	 *
	 * @param in
	 *            Buffered bit stream of the file to be decompressed.
	 * @param out
	 *            Buffered bit stream writing to the output file.
	 */
	public void decompress(BitInputStream in, BitOutputStream out){
		//read "magic" number from file
		int bits = in.readBits(BITS_PER_INT);
		
		//only process bits if tree used to compress was Huffman tree
		if(bits != HUFF_TREE) {
			throw new HuffException("illegal header starts with " + bits);
		}
		
		//read bits from file and traverse root-to-leaf paths
		HuffNode root = readTreeHeader(in);
		readCompressedBits(root, in, out);
		out.close();
	}
	
	private HuffNode readTreeHeader(BitInputStream in) {
		while(true) {
			int bits = in.readBits(1);
			
			if(bits == -1) {
				throw new HuffException("unable to read bits");
			}
			
			//if the bit read is 0 (internal node), call recursive algorithm to for HuffTree to left and HuffTree to right, returning a new HuffNode
			if(bits == 0) {
				HuffNode left = readTreeHeader(in);
				HuffNode right = readTreeHeader(in);
				return new HuffNode(0, 0, left, right);
			}
			
			//otherwise, the bit is a leaf, signifying the end of the tree
			else {
				int value = in.readBits(BITS_PER_WORD + 1);
				return new HuffNode(value, 0, null, null);
			}
		}
	}
	
	private void readCompressedBits(HuffNode root, BitInputStream in, BitOutputStream out) {
		HuffNode current = root;
		
		while(true) {
			//read 1 bit
			int bits = in.readBits(1);
			
			if(bits == -1) {
				throw new HuffException("bad input, no PSEUDO_EOF");
			}
			
			else {
				//path dictates to go left
				if(bits == 0) {
					current = current.myLeft;
				}
				
				//path dictates to go right
				else {
					current = current.myRight;
				}
				
				//if at a leaf
				if(current.myLeft == null && current.myRight == null) {
					//if value stored in leaf is PSEUDO_EOF, break out of if statement
					if(current.myValue == PSEUDO_EOF) {
						break;
					}
					
					//else, write out character stored in leaf
					else {
						out.writeBits(BITS_PER_WORD, current.myValue);
						current = root;
					}
				}
			}
		}
	}
}