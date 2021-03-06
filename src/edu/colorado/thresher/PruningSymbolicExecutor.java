package edu.colorado.thresher;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphTransitiveClosure;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.functions.Function;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.traverse.DFS;
import com.ibm.wala.util.intset.OrdinalSet;

/**
 * symbolic executor that prunes the call graph to *only* the call graph
 * relevant to the current constraints at interprocedural boundaries
 * 
 * @author sam
 * 
 */
public class PruningSymbolicExecutor extends OptimizedPathSensitiveSymbolicExecutor {

  // private final Map<Constraint,Set<CGNode>> reachableCache;
  // transitive closure of the "calls" relation
  final Map<CGNode, OrdinalSet<CGNode>> callGraphTransitiveClosure;
  private final Logger logger;

  public PruningSymbolicExecutor(CallGraph callGraph, Logger logger) {
    super(callGraph, logger);
    this.logger = logger;
    // this.reachableCache = new HashMap<Constraint, Set<CGNode>>();

    final CallGraph localCopy = callGraph; // avoid compiler error
    Map<CGNode, Collection<CGNode>> resultMap = CallGraphTransitiveClosure.collectNodeResults(callGraph,
        new Function<CGNode, Collection<CGNode>>() {
          public Set<CGNode> apply(CGNode node) {
            return Util.iteratorToSet(localCopy.getSuccNodes(node));
          }
        });
    callGraphTransitiveClosure = CallGraphTransitiveClosure.transitiveClosure(callGraph, resultMap);
  }

  public boolean isCalledByClassInit(CGNode node) {
    return callGraphTransitiveClosure.get(WALACFGUtil.getFakeWorldClinitNode(callGraph)).contains(node);
  }
  
  @Override
  public Iterator<CGNode> getCallers(IPathInfo path, Graph<CGNode> graph) {
    if (!Options.CALLGRAPH_PRUNING) return this.callGraph.getPredNodes(path.getCurrentNode());
    IPathInfo copy = path.deepCopy();
    CGNode node = copy.getCurrentNode();

    // first, consider special case for when the caller is a class initializer
    Set<CGNode> preds = Util.iteratorToSet(this.callGraph.getPredNodes(node));
    // TODO: what if there are several modifiers and only one is a clinit?
    if (preds.size() == 1) { // &&
                             // preds.iterator().next().getMethod().isClinit())
                             // {
      return preds.iterator();
    }
    // else, caller is not a class init...
    copy.removeAllLocalConstraintsFromQuery(); // eliminate all non-heap
                                               // constraints (constraints on
                                               // function params should be only
                                               // local constraints left)
    Util.Debug("after removing all local" + path);
    if (copy.foundWitness())
      return this.callGraph.getPredNodes(node); // no heap constraints left to
                                                // produce, so can't prune any
                                                // callers

    // compute set of all CGNode's that might affect our query
    Map<Constraint, Set<CGNode>> queryModMap = copy.getModifiersForQuery();
    // TODO: check for class inits in modifiers?
    Set<CGNode> modifiers = Util.flatten(queryModMap.values());

    if (Options.DEBUG)
      for (CGNode nod : modifiers)
        Util.Debug("possible modifier " + nod);

    // return computeReducedCallerSet(queryModMap, preds);
    return computeReducedCallerSet(modifiers, preds);
  }

  private Iterator<CGNode> computeReducedCallerSet(Set<CGNode> modifiers, Set<CGNode> toPrune) {
    // assume everything is reachable from the class inits
    for (CGNode node : modifiers) {
      if (isCalledByClassInit(node)) return toPrune.iterator();
    }
    Set<CGNode> reachable = getReachable(modifiers, toPrune, true);

    // TODO: this is unecessary (could just modify caller list in method call),
    // but want to be explicit about what's pruned
    // if (Options.DEBUG) {
    for (CGNode pruneMe : toPrune) {
      if (!reachable.contains(pruneMe)) {
        Util.Print("pruned " + pruneMe);
        logger.log("prunedCaller");
      }// else Util.Debug("caller " + toPrune + " reachable");
    }
    // }
    toPrune.retainAll(reachable);
    return toPrune.iterator();
  }
  
  
  /**
   * Ask flow-insensitively: can we get from src to snk?
   * @param snk - node we are trying to reach
   * @param src - node we are starting from
   */
  boolean isReachableFrom(CGNode snk, CGNode src) {
    OrdinalSet<CGNode> reachable = callGraphTransitiveClosure.get(src);
    return reachable.contains(snk);
  }

  /**
   * @return true if a path from srcNode to snkNode exists
   */
  boolean feasiblePathExists(CGNode srcNode, ISSABasicBlock srcBlk, CGNode snkNode, ISSABasicBlock snkBlk) {
    Set<ISSABasicBlock> reachableBlks = DFS.getReachableNodes(srcNode.getIR().getControlFlowGraph(), 
        Collections.singleton(srcBlk));
    if (srcNode == snkNode) {
       if (reachableBlks.contains(snkBlk)) return true;
       // TODO: need to look for other calls of srcNode here? in general, yes obviously, but
       // given the usage of this function, there may be some scope issues
    } else {
      Set<CGNode> reachable = HashSetFactory.make();
      for (CGNode callee : WALACFGUtil.getCallTargetsInBlocks(reachableBlks, srcNode, callGraph)) {
        reachable.addAll(OrdinalSet.toCollection(callGraphTransitiveClosure.get(callee)));
      }
      if (reachable.contains(snkNode)) return true;
      reachable = getReachableStartingBackwardsFrom(Collections.singleton(srcNode), 
          Collections.singleton(snkNode), reachable, true);
    }
    return false;
  }

  /**
   * Ask flow-sensitively: can we get from src to snk?
   */
  boolean feasiblePathExists(CGNode src, CGNode snk) {
    if (!Options.CALLGRAPH_PRUNING) return true;
    if (isCalledByClassInit(src)) return true; // assume everything is reachable from the class inits
    Set<CGNode> reachable = getReachable(Collections.singleton(src), Collections.singleton(snk), false);
    return reachable.contains(snk);
  }

  /**
   * @param srcs - seeds for the search
   * @param snks
   *          - nodes we are trying to reach from the search (needed for
   *          optimization)
   * @return - the set of nodes whose entrypoints are reachable from srcs (or a subset 
   *          containing all nodes in snks)
   */
  public Set<CGNode> getReachable(Collection<CGNode> srcs, Set<CGNode> snks, final boolean includeCallers) {
    // all nodes that are completely reachable
    Set<CGNode> reachable = HashSetFactory.make();

    for (CGNode src : srcs) {
      reachable.addAll(OrdinalSet.toCollection(callGraphTransitiveClosure.get(src)));
    }
    if (reachable.containsAll(snks)) return reachable; // early return if we cover everything
    reachable.add(callGraph.getFakeRootNode()); // don't want to model control
                                                // flow among entrypoints
    return getReachableStartingBackwardsFrom(srcs, snks, reachable, includeCallers);
  }
  
  
  Set<CGNode> getReachableStartingBackwardsFrom(Collection<CGNode> srcs, 
                                                Set<CGNode> snks, Set<CGNode> reachable, final boolean includeCallers) {
    // nodes whose entrypoints are reachable, but some callees may not be reachable
    Set<CGNode> partiallyReachable = HashSetFactory.make();
    for (;;) {
      boolean progress = false;
      // callers of a node in srcs that we have yet to explore
      Set<CGNode> frontier = HashSetFactory.make();
      // not all elements of snks are directly reachable
      for (CGNode src : srcs) {
        // for each caller
        for (Iterator<CGNode> callerNodes = callGraph.getPredNodes(src); callerNodes.hasNext();) {
          CGNode caller = callerNodes.next();
          // class inits should be handled separately...
          Util.Assert(!caller.getMethod().isClinit());

          // avoid redundant exploration
          if (reachable.contains(caller)) continue;
          progress = true;
          frontier.add(caller);
          // Manu's optimization; do FI check (using callgraph) on nodes reachable from caller first.
          // if no nodes in toPrune are reachable according to the callgraph, we
          // needn't do the expensive intraprocedural search
          Collection<CGNode> reachableFromCaller = OrdinalSet.toCollection(callGraphTransitiveClosure.get(caller));

          if (Util.intersectionNonEmpty(reachableFromCaller, snks)) {
            partiallyReachable.add(caller);
            Set<ISSABasicBlock> possibleStartBlocks = HashSetFactory.make();
            IR ir = caller.getIR();
            SSACFG cfg = ir.getControlFlowGraph();
            // for each context for the the caller
            for (Iterator<CallSiteReference> sites = callGraph.getPossibleSites(caller, src); sites.hasNext();) { 
              CallSiteReference site = sites.next();
              ISSABasicBlock[] blks = ir.getBasicBlocksForCall(site);
              for (int i = 0; i < blks.length; i++) {
                // get succs because we want to exclude the block containing the 
                // call we came from
                Iterator<ISSABasicBlock> succBlks = cfg.getSuccNodes(blks[i]);
                while (succBlks.hasNext()) {
                  possibleStartBlocks.add(succBlks.next());
                }
              }
            }
            
            Set<ISSABasicBlock> localReachable = DFS.getReachableNodes(cfg, possibleStartBlocks);
            Set<CGNode> callees = WALACFGUtil.getCallTargetsInBlocks(localReachable, caller, callGraph);
            for (CGNode callee : callees) {
              if (reachable.add(callee)) {
                reachable.addAll(OrdinalSet.toCollection(callGraphTransitiveClosure.get(callee)));
              }
            }
            if (reachable.containsAll(snks)) return reachable; // early return if we cover everything
          } else {
             if (includeCallers) reachable.add(caller);
            reachable.addAll(reachableFromCaller);
          }
        }
      } // end for (;;)
      if (!progress) break;
      srcs = frontier;
    }
    // add partially reachable to the reachable set; we only kept this set to prevent unsound skipping during the search
    if (includeCallers) reachable.addAll(partiallyReachable);
    return reachable;
  }
}
