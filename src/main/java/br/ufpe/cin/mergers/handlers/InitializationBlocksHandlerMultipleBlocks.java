package br.ufpe.cin.mergers.handlers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import br.ufpe.cin.app.JFSTMerge;
import br.ufpe.cin.exceptions.TextualMergeException;
import br.ufpe.cin.files.FilesManager;
import br.ufpe.cin.mergers.TextualMerge;
import br.ufpe.cin.mergers.util.MergeContext;
import de.ovgu.cide.fstgen.ast.FSTNode;
import de.ovgu.cide.fstgen.ast.FSTTerminal;

/**
 * As in semistructured merge the ID is used for matching the nodes and initialization blocks don't have it, during
 * the superimposition of these blocks they are duplicated. This handler matches the nodes mainly by the level of
 * content insertion compared to the blocks on base, also checking for possible dependency between blocks and solving 
 * variable renaming conflicts.
 * 
 *  @author Alice Borner
 *  
 */
public class InitializationBlocksHandlerMultipleBlocks implements ConflictHandler {
    
    private final static String INITIALIZATION_BLOCK_IDENTIFIER = "InitializerDecl";	
    private final static String LINE_BREAKER_REGEX = "\\r\\n(\\t)*";	
    
    // conflict markers
    private final static String CONFLICT_MARKER = "<<<<<<<";
    private final static String CONLFICT_MINE = "<<<<<<< MINE";
    private final static String CONFLICT_YOURS = ">>>>>>> YOURS";
    private final static String CONFLICTS_SEPARATOR = "=======";
    
	public void handle(MergeContext context) throws TextualMergeException {
		
        List<FSTNode> leftNodes = findInitializationBlocks(context.addedLeftNodes);
        List<FSTNode> rightNodes = findInitializationBlocks(context.addedRightNodes);
        List<FSTNode> baseNodes = findInitializationBlocks(context.deletedBaseNodes);

        Pair<List<InitializationBlocksHandlerNode>, List<InitializationBlocksHandlerNode>> editedNodesPair =
        		selectEditedNodes(leftNodes, baseNodes, rightNodes);
        
        List<InitializationBlocksHandlerNode> leftEditedNodes = editedNodesPair.getLeft();
        List<InitializationBlocksHandlerNode> rightEditedNodes = editedNodesPair.getRight();

        Pair<List<FSTNode>, List<FSTNode>> addedNodes = selectAddedNodes(leftNodes, baseNodes, rightNodes,
        		leftEditedNodes, rightEditedNodes);
       
        Pair<List<FSTNode>, List<FSTNode>> deletedNodes = selectDeletedNodes(leftNodes, baseNodes, rightNodes,
        		leftEditedNodes, rightEditedNodes);
        
    	for(FSTNode baseNode : baseNodes) {
    		InitializationBlocksHandlerNode leftNode = getEditedNodeByBaseNode(leftEditedNodes, baseNode);
    		InitializationBlocksHandlerNode rightNode = getEditedNodeByBaseNode(rightEditedNodes, baseNode);
    		
    		mergeContentAndUpdateAST(leftNode, baseNode, rightNode, context, deletedNodes);
    	}

    	// there are static global variables and left/right added blocks, so could be the case of a dependency
    	Map<FSTNode, List<FSTNode>> commonVarsNodesMap = getCommonAccessGlobalVariablesNodes(addedNodes);
    	if(!commonVarsNodesMap.isEmpty())
    		mergeDependentAddedNodesAndUpdateAST(context, commonVarsNodesMap);
    }
	
    private List<InitializationBlocksHandlerNode> defineEditedNodes(List<FSTNode> addedCandidates,
    		List<FSTNode> deletedCandidates) {
    	
    	List<InitializationBlocksHandlerNode> editedNodes = new ArrayList<InitializationBlocksHandlerNode>();

    	for(FSTNode node : addedCandidates) {
    		Pair<FSTNode, Double> maxInsertionPair = maxInsertionLevel(node, deletedCandidates);
    		Pair<FSTNode, Double> maxSimilarityPair = maxSimilarity(node, deletedCandidates);
    		FSTNode baseNode = getBaseNode(maxInsertionPair, maxSimilarityPair);
    		
    		if(baseNode != null && (maxInsertionPair.getValue() > 0.7 || maxSimilarityPair.getValue() > 0.5)) {
    			InitializationBlocksHandlerNode editedNode;
    				editedNode = new InitializationBlocksHandlerNode(baseNode, node);
    			editedNodes.add(editedNode);
    		}
    	}
    	
    	return editedNodes;
    }

	private void mergeContentAndUpdateAST(InitializationBlocksHandlerNode leftNode, FSTNode baseNode,
			InitializationBlocksHandlerNode rightNode, MergeContext context, 
			Pair<List<FSTNode>,List<FSTNode>> deletedNodes) throws TextualMergeException {
		
		String baseContent = ((FSTTerminal) baseNode).getBody();
		String leftContent = (leftNode != null) ? ((FSTTerminal) leftNode.getEditedNode()).getBody() : "";
		String rightContent = (rightNode != null) ? ((FSTTerminal) rightNode.getEditedNode()).getBody() : "";

		if(leftNode != null && rightNode != null) {
			// both branches edited the node
			
		    String mergedContent = TextualMerge.merge(leftContent, baseContent, rightContent, 
					JFSTMerge.isWhitespaceIgnored);
		    
			if (mergedContent != null && mergedContent.contains(CONFLICT_MARKER)) {
				// there is a conflict, check if it's only a renaming conflict to fix it
				mergedContent = checkVariableRenamingConflict(mergedContent, baseContent);
			} 
            
            FilesManager.findAndReplaceASTNodeContent(context.superImposedTree, leftContent, mergedContent);
            FilesManager.findAndDeleteASTNode(context.superImposedTree, rightContent);
            
    		if (mergedContent.contains(CONFLICT_MARKER)) {
    			context.initializationBlocksConflicts++;
    		}
            
        } else if(leftNode == null && rightNode == null) {
        	// any branch edited the node, delete one of the nodes content to don't have duplicates
            FilesManager.findAndDeleteASTNode(context.superImposedTree, baseContent);
		} else {
			// one of the branches edited or deleted the node
			if (leftNode == null) {
				// left deleted or right edited the node 
				List<FSTNode> leftDeletedNodes = deletedNodes.getLeft();
				mergeDeletedEditedContentAndUpdateAST(context, baseNode, leftDeletedNodes, rightNode, true);
			}
			
			if (rightNode == null) {  
				// right deleted or left edited the node 
				List<FSTNode> rightDeletedNodes = deletedNodes.getRight();
				mergeDeletedEditedContentAndUpdateAST(context, baseNode, rightDeletedNodes, leftNode, false);
			}
        }
	}
	

	private Pair<List<InitializationBlocksHandlerNode>, List<InitializationBlocksHandlerNode>> selectEditedNodes
		(List<FSTNode> leftNodes, List<FSTNode> baseNodes, List<FSTNode> rightNodes) {
    	
    	// Finding deleted candidates
    	List<FSTNode> leftDeletedCandidates = removeContainedNodesFromList(baseNodes, leftNodes);
    	List<FSTNode> rightDeletedCandidates = removeContainedNodesFromList(baseNodes, rightNodes);

    	// Finding added candidates
    	List<FSTNode> leftAddedCandidates = removeContainedNodesFromList(leftNodes, baseNodes);
    	List<FSTNode> rightAddedCandidates = removeContainedNodesFromList(rightNodes, baseNodes);
    	
    	// Defining edited nodes by similarity and/or insertion level
    	List<InitializationBlocksHandlerNode> leftEditedNodes = defineEditedNodes(leftAddedCandidates,
    			leftDeletedCandidates);
    			
    	List<InitializationBlocksHandlerNode> rightEditedNodes = defineEditedNodes(rightAddedCandidates,
    			rightDeletedCandidates);
    	
    	return Pair.of(leftEditedNodes, rightEditedNodes);
    }
	
	private Pair<List<FSTNode>, List<FSTNode>> selectDeletedNodes(List<FSTNode> leftNodes, List<FSTNode> baseNodes,
			List<FSTNode> rightNodes, List<InitializationBlocksHandlerNode> leftEditedNodes, 
			List<InitializationBlocksHandlerNode> rightEditedNodes) {
		
		// Finding deleted candidates
    	List<FSTNode> leftDeletedCandidates = removeContainedNodesFromList(baseNodes, leftNodes);
    	List<FSTNode> rightDeletedCandidates = removeContainedNodesFromList(baseNodes, rightNodes);
    	
      	// Defining deleted nodes removing edited ones from the candidates list
    	List<FSTNode> leftDeletedNodes = removeContainedNodesFromList(leftDeletedCandidates,
    			getBaseNodes(leftEditedNodes));
    	List<FSTNode> rightDeletedNodes = removeContainedNodesFromList(rightDeletedCandidates,
    			getBaseNodes(rightEditedNodes));
    	
    	return Pair.of(leftDeletedNodes, rightDeletedNodes);
	}
	
	private Pair<List<FSTNode>, List<FSTNode>> selectAddedNodes(List<FSTNode> leftNodes, List<FSTNode> baseNodes,
			List<FSTNode> rightNodes, List<InitializationBlocksHandlerNode> leftEditedNodes, 
			List<InitializationBlocksHandlerNode> rightEditedNodes) {
    	
		// Finding added candidates
    	List<FSTNode> leftAddedCandidates = removeContainedNodesFromList(leftNodes, baseNodes);
    	List<FSTNode> rightAddedCandidates = removeContainedNodesFromList(rightNodes, baseNodes);
    	
     	// Defining added nodes removing edited ones from the candidates list
    	List<FSTNode> leftAddedNodes = removeContainedNodesFromList(leftAddedCandidates,
    			getBaseNodes(leftEditedNodes));
    	List<FSTNode> rightAddedNodes = removeContainedNodesFromList(rightAddedCandidates,
    			getBaseNodes(rightEditedNodes));
    	
    	return Pair.of(leftAddedNodes, rightAddedNodes);
	}
	
	private void mergeDeletedEditedContentAndUpdateAST(MergeContext context, FSTNode baseNode,
			List<FSTNode> deletedNodes, InitializationBlocksHandlerNode node, boolean isLeftNode) 
					throws TextualMergeException {
		
		String baseContent = ((FSTTerminal) baseNode).getBody();
		String editedNodeContent = ((FSTTerminal) node.getEditedNode()).getBody();
		String otherNodeContent = baseContent;
		
		if(containsNode(deletedNodes, baseNode)) {
            FilesManager.findAndDeleteASTNode(context.superImposedTree, baseContent);
            otherNodeContent = "";
    	}
		
		String mergedContent;
		
		// order of parameters changes depending on which branch changes/deleted the node
		if(isLeftNode) {
			mergedContent = TextualMerge.merge(otherNodeContent, baseContent, editedNodeContent, 
					JFSTMerge.isWhitespaceIgnored);
		} else {
			mergedContent = TextualMerge.merge(editedNodeContent, baseContent, otherNodeContent, 
					JFSTMerge.isWhitespaceIgnored);
		}
		
		FilesManager.findAndReplaceASTNodeContent(context.superImposedTree, editedNodeContent, mergedContent);
		FilesManager.findAndDeleteASTNode(context.superImposedTree, otherNodeContent);

		if (mergedContent != null && mergedContent.contains(CONFLICT_MARKER)) {
			context.initializationBlocksConflicts++;
		}
	}

	private Map<FSTNode,List<FSTNode>> getCommonAccessGlobalVariablesNodes(Pair<List<FSTNode>,
			List<FSTNode>> addedNodes) {
		
		Map<FSTNode,List<FSTNode>> commonVarsNodesMap = new HashMap<>();
		List<FSTNode> leftAddedNodes = addedNodes.getLeft();
		List<FSTNode> rightAddedNodes = addedNodes.getRight();

		for(FSTNode leftNode : leftAddedNodes) {
			for(FSTNode rightNode : rightAddedNodes) {
// TODO: remove! algorithm
//				leftGlobalVariables = getGlobalVariables(leftNode)
//				rightGlobalVariables = getGlobalVariables(rightNode)
//				commonGlobalVariables = leftGlobalVariables.remove(rightGlobalVariables)
		//
//				if !commonGlobalVariables.isEmpty()
//					commonVariablesMap.put(leftNode, addToList(rightNode))
			}
		}
		
		
       return commonVarsNodesMap;
	}
	
// TODO: remove! algorithm
//List<Var> getGlobalVariables(node)
//	for line in node.getLines() 
//		if(line.isVarAssignment() and !line.isLocalVarDeclarationOrAssignment())
//			nodeGlobalVariables.add(line.getVar())
//	return nodeGlobalVariables
	
	private void mergeDependentAddedNodesAndUpdateAST(MergeContext context, Map<FSTNode, List<FSTNode>>
		commonVarsNodesMap) throws TextualMergeException {
		
		
		// TODO: remove! algorithm
		//for leftConflict in leftConflicts
		//leftConflictContent = leftConflict.getContent()
		//for rightConflict in commonVariablesMap.get(leftConflict) 
		//rightConflictContent.append(rightConflict)
		//
		//conflictNode = getConflictNode(leftConflictContent, rightConflictContent)
		//finalNodes.add(conflictNode)		
//	TODO: update implementation with new algorithm
//		for(String variable : dependentNodes) {
//			String baseContent = "";
//			String mergedContent = null;
//			FSTNode leftNode = findNodeUsesVariable(context.addedLeftNodes, variable);
//			FSTNode rightNode = findNodeUsesVariable(context.addedRightNodes, variable);
//			
//			if(leftNode != null && rightNode != null) {
//				String leftContent = ((FSTTerminal) leftNode).getBody();
//				String rightContent = ((FSTTerminal) rightNode).getBody();
//				
//				mergedContent = TextualMerge.merge(leftContent, baseContent, rightContent, 
//						JFSTMerge.isWhitespaceIgnored);
//				
//	            FilesManager.findAndReplaceASTNodeContent(context.superImposedTree, leftContent, mergedContent);
//	            FilesManager.findAndDeleteASTNode(context.superImposedTree, rightContent);
//	            
//				// statistics
//				if (mergedContent != null && mergedContent.contains(CONFLICT_MARKER)) //has conflict
//					context.initializationBlocksConflicts++;
//			}
//		}
	}
	
	private InitializationBlocksHandlerNode getEditedNodeByBaseNode(List<InitializationBlocksHandlerNode> nodesList,
			FSTNode baseNode) {
		
		List<InitializationBlocksHandlerNode> nodes = nodesList.stream().filter(node -> node.getBaseNode()
				.equals(baseNode))
				.collect(Collectors.toList());
		
		if(!nodes.isEmpty())
			return nodes.get(0);
		else 
			return null;
	}
    
    private List<FSTNode> getBaseNodes(List<InitializationBlocksHandlerNode> initializationHandlerNodes) {
    	
    	return initializationHandlerNodes.stream().collect(Collectors.mapping(InitializationBlocksHandlerNode
    			::getBaseNode,
				Collectors.toList()));
    }

    private List<FSTNode> removeContainedNodesFromList(Collection<FSTNode> nodesList, 
    		Collection<FSTNode> nodesToCheck) {
    	
    	return nodesList.stream().filter(node -> !containsNode(nodesToCheck, node)).collect(Collectors.toList());
    }
    
    private boolean containsNode(Collection<FSTNode> nodes, FSTNode node) {
    	
    	for(FSTNode listNode : nodes) {
    		String listNodeContent = (listNode != null) ? ((FSTTerminal) listNode).getBody() : "";
            String nodeContent = (node != null) ? ((FSTTerminal) node).getBody() : "";
            
            if(listNodeContent.equals(nodeContent))
            	return true;
    	}
    	
    	return false;
    }
  
    private static List<FSTNode> findInitializationBlocks(List<FSTNode> nodes) {
        return nodes.stream()
                .filter(p -> p.getType().equals(INITIALIZATION_BLOCK_IDENTIFIER))
                .collect(Collectors.toList());
    }
    
    private static Pair<FSTNode, Double> maxInsertionLevel(FSTNode node, List<FSTNode> nodes) {
    	
    	Map<FSTNode, Double> nodesInsertionLevelMap = new HashMap<>();
    	String nodeBody = ((FSTTerminal) node).getBody();
    	String nodeLines = StringUtils.substringBetween(nodeBody, "{", "}").trim();
    	List<String> splitNodeContent = Arrays.asList(nodeLines.split(LINE_BREAKER_REGEX));

    	for(FSTNode pairNode : nodes) {
        	String pairNodeBody = ((FSTTerminal) pairNode).getBody();
        	String pairNodeLines = StringUtils.substringBetween(pairNodeBody, "{", "}").trim();
        	List<String> splitPairNodeContent = Arrays.asList(pairNodeLines.split(LINE_BREAKER_REGEX));
        	
        	double numOfInsertions = 0;
        	for(String content : splitNodeContent) {
        		for(String pairContent : splitPairNodeContent) {
        			if(!content.trim().isEmpty() && !pairContent.trim().isEmpty()
        					&& content.trim().equals(pairContent.trim()))
        				numOfInsertions++;
        		}
        	}
        	
        	double insertionLevel = numOfInsertions / splitPairNodeContent.size();
        	
        	nodesInsertionLevelMap.put(pairNode, insertionLevel);
    	}
    	
    	FSTNode nodeMaxValue = getNodeWithHighestValue(nodesInsertionLevelMap);
    	return Pair.of(nodeMaxValue, nodesInsertionLevelMap.get(nodeMaxValue));
    }
    
    private static Pair<FSTNode, Double> maxSimilarity(FSTNode node, List<FSTNode> nodes) {
    	
    	String nodeContent = ((FSTTerminal) node).getBody();
    	Map<FSTNode, Double> nodesSimilarityLevelMap = new HashMap<>();
    	
    	for(FSTNode pairNode : nodes) {
        	String pairNodeContent = ((FSTTerminal) pairNode).getBody();
        	double similarity = FilesManager.computeStringSimilarity(nodeContent, pairNodeContent);
        	nodesSimilarityLevelMap.put(pairNode, similarity);
    	}
    	
    	FSTNode nodeMaxValue = getNodeWithHighestValue(nodesSimilarityLevelMap);
    	return Pair.of(nodeMaxValue, nodesSimilarityLevelMap.get(nodeMaxValue));
    }
    
    private static FSTNode getNodeWithHighestValue(Map<FSTNode, Double> nodesMap) {
    	
    	if(nodesMap.entrySet().isEmpty())
    		return null;
    	
    	return Collections.max(nodesMap.entrySet(), Comparator.comparingDouble(Map.Entry::getValue))
    			.getKey();
    }
    
    private FSTNode getBaseNode(Pair<FSTNode, Double> maxInsertionPair, Pair<FSTNode, Double> maxSimilarityPair) {
    	
    	FSTNode baseNode;
    	
    	if(maxInsertionPair.getKey() != null && maxInsertionPair.getValue() != 0) {
    		baseNode = maxInsertionPair.getKey();
    	} else {
    		baseNode =  maxSimilarityPair.getKey();
    	}
    	
    	return baseNode;
    }

    // TODO: improve implementation to be more general
    private String checkVariableRenamingConflict(String mergedContent, String baseContent) {
    	
		String beforeConflict = StringUtils.substringBefore(mergedContent, CONLFICT_MINE);
		String afterConflict = StringUtils.substringAfter(mergedContent, CONFLICT_YOURS);
		String conflictContent = StringUtils.substringBetween(mergedContent, beforeConflict, afterConflict).trim();
		
		String leftContent = StringUtils.substringBetween(mergedContent, CONLFICT_MINE, CONFLICTS_SEPARATOR).trim();
		String rightContent = StringUtils.substringBetween(mergedContent, CONFLICTS_SEPARATOR, CONFLICT_YOURS).trim();

		/**
		 * parts[1] contains the name of the variable and parts[3] the content to be compared later if 
		 * one branch changed only the name and the other the content. 
		 */
		String[] leftParts = leftContent.split(" ");
		String[] rightParts = rightContent.split(" ");
		
		String baseVarLeft = findVarInBaseContent(leftParts[0] + ".*" + leftParts[1] + ".*;", baseContent);
		String baseVarRight = findVarInBaseContent(rightParts[0] + ".*" + rightParts[1] + ".*;", baseContent);
		
		if(baseVarLeft != null || baseVarRight != null) {
			// variable from left or right was found in base
			
		    String baseVarContent = baseVarLeft != null ? baseVarLeft : baseVarRight;
			String[] baseParts = baseVarContent.split(" ");
			String newVarContent = "";
			
			// if variable name from base and left is the same and value from base and right is the same
			if(isNameAndContentChangedByDifferentBranches(baseParts, leftParts, rightParts)) 
				newVarContent = leftParts[0] + " " + rightParts[1] + " " + leftParts[2] + " " + leftParts[3];
			
			if(isNameAndContentChangedByDifferentBranches(baseParts, rightParts, leftParts)) 
				newVarContent = leftParts[0] + " " + leftParts[1] + " " + leftParts[2] + " " + rightParts[3];
			
			if(!newVarContent.isEmpty())
				mergedContent = mergedContent.replace(conflictContent, newVarContent);
		}
		
		return mergedContent;
	}
	
	private boolean isNameAndContentChangedByDifferentBranches(String[] baseParts, String[] nameDiffBranch,
			String[] contentDiffBranch) {
		
		return baseParts[1].equals(nameDiffBranch[1]) && baseParts[3].equals(contentDiffBranch[3])
				&& !baseParts[1].equals(contentDiffBranch[1]) && !baseParts[3].equals(nameDiffBranch[3]);
	}
	
	private String findVarInBaseContent(String varRegex, String baseContent) {
		
		Pattern pattern = Pattern.compile(varRegex);
        Matcher matcher = pattern.matcher(baseContent);
        
        String baseVar = null;
        
        if(matcher.find()) 
        	baseVar = matcher.group();

        return baseVar;
	}
}

class InitializationBlocksHandlerNode {
	
	private FSTNode baseNode;
	private FSTNode editedNode;

	public InitializationBlocksHandlerNode(FSTNode baseNode, FSTNode editedNode) {
		this.baseNode = baseNode;
		this.editedNode = editedNode;
	}

	public FSTNode getBaseNode() {
		return baseNode;
	}

	public void setBaseNode(FSTNode baseNode) {
		this.baseNode = baseNode;
	}

	public FSTNode getEditedNode() {
		return editedNode;
	}

	public void setEditedNode(FSTNode editedNode) {
		this.editedNode = editedNode;
	}
}
