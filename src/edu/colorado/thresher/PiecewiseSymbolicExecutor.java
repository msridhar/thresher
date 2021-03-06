package edu.colorado.thresher;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.util.graph.impl.GraphInverter;
import com.ibm.wala.util.graph.traverse.BFSPathFinder;
import com.ibm.wala.util.graph.traverse.DFS;
import com.ibm.wala.util.intset.IntIterator;

public class PiecewiseSymbolicExecutor extends PruningSymbolicExecutor {

  public PiecewiseSymbolicExecutor(CallGraph callGraph, Logger logger) {
    super(callGraph, logger);
  }

  // debug only
  int depthCounter = 0;

  public boolean handlePiecewiseExecutionMethodBased(final IPathInfo path) {
    int depth = depthCounter++;
    final CGNode startNode = path.getCurrentNode();
    final IPathInfo copy = path.deepCopy();
    // eliminate all non-heap (should only be constraints on function params left)
    copy.removeAllLocalConstraintsFromQuery(); 
    Util.Debug("after removing all local constraints: " + copy);
    if (copy.foundWitness()) { // check to make sure we didn't remove everything
      Util.Debug("all non-local constraints produced; returning witness");
      path.declareFakeWitness();
      return true;
    }

    // check summary
    if (isPathInSummary(path)) {
      Util.Debug("refuted by summary");
      return false;
    }
    
    CGNode fakeWorldClinit = WALACFGUtil.getFakeWorldClinitNode(callGraph);
    
    Map<Constraint,Set<CGNode>> constraintModMap = copy.getModifiersForQuery();
    boolean tryFakeWorldClinit = true;
    
    // no sense in trying fakeWorldClinit if we still need to produce some constraint
    // that can't be produced there 
    for (Constraint constraint : constraintModMap.keySet()) {
      if (!constraintModMap.get(constraint).contains(fakeWorldClinit)) {
        tryFakeWorldClinit = false;
        break;
      }
    }
        
    // get potential producers for constraints
    Set<CGNode> producers = Util.flatten(constraintModMap.values());
    if (Options.DEBUG) {
      Util.Debug("MAP: ");
      for (Constraint constraint : constraintModMap.keySet()) {
        Util.Debug(constraint + " ===>\n" + Util.printCollection(constraintModMap.get(constraint)));
      }
      
      for (CGNode producer : producers)
        Util.Debug("producer: " + producer);
    }
    
    /**
     * strategy -
     * (1) make count of producers, explore highest count first
     * (2) if one of the constraints can't be produced by <clinit>, don' try it 
     * (3) if there's something only produced by one method and the method doesn't produce it,
     *    we have a wholesale refutation
     * 
     * TODO: if producer is only called from class init, count it as class init
     */
    
        
    if (tryFakeWorldClinit) {
      IPathInfo classInitPath = copy.deepCopy();
      // handle class initializer case first
      classInitPath.enterCallFromJump(fakeWorldClinit);
      classInitPath.setCurrentNode(fakeWorldClinit);
      Util.Debug("about to try fakeWorldClinit");
      boolean result = this.handleFakeWorldClinit(classInitPath);
      if (result) return true; // found witness in fakeWorldClinit
      // else, refuted; try other producers
    }
    
    // have we been at the function boundary for this node before?
    if (!path.addSeen(path.getCurrentNode())) {
      Util.Debug("have seen producer " + path.getCurrentNode() + " before, refuting");
      return false;
    }

    for (CGNode producer : producers) {
      IPathInfo newPath = copy.deepCopy();
      Util.Debug("start node is " + startNode + " at depth " + depth + " on path " + newPath);
      // do callgraph feasability checks

      if (!newPath.addSeen(producer)) {
        // already seen producer node... keep going
        // TODO: this is unsound -- figure out what we should really do here.
        // (1) is the producer the same as the current node or called by the
        // current node?
        // yes; don't want to consider the effect of calling this twice
        Util.Debug("have seen producer " + producer + " before, continuing");
        continue;
      }
      Util.Debug("trying producer " + producer);

      // is the producer in the class initializers?
      if (producer.getMethod().isClinit() || producer.equals(fakeWorldClinit)) { 
        // producer is class initializer; we already handled this case
        continue;
      }

      // is the start node reachable from the producer?
      if (feasiblePathExists(producer, startNode)) {
        newPath.enterCallFromJump(producer);
        IPathInfo newPathCopy = newPath.deepCopy();
        // (1) entering producer from caller (i.e., start from from exit point of producer)
        if (executeToProcedureBoundary(newPathCopy, producer)) {
          path.declareFakeWitness();
          return true;
        } // else, refuted
        
        // (2) entering producer from callee (i.e., start from each function call in the producer)
        Iterator<CallSiteReference> sites = producer.iterateCallSites();
        while (sites.hasNext()) { 
          CallSiteReference site = sites.next();
          Set<CGNode> nodes = callGraph.getNodes(site.getDeclaredTarget());
          boolean reachable = false;
          for (CGNode node : nodes) {
            if (isReachableFrom(startNode, node)) {
              reachable = true;
              break;
            }
          }
          if (!reachable) continue; // can't possibly enter producer from this call site
          IR producerIR = producer.getIR();
          SSACFG producerCFG = producerIR.getControlFlowGraph();
          SSAInstruction[] instrs = producerIR.getInstructions();
          IntIterator iter = producerIR.getCallInstructionIndices(site).intIterator();
          while (iter.hasNext()) {
            int callIndex = iter.next();
            SSAInstruction instr = instrs[callIndex];
            if (Options.DEBUG) Util.Debug("Trying call instr " + instr);
            Util.Assert(instr instanceof SSAInvokeInstruction, "Expecting a call instruction, found " + instr);
            SSACFG.BasicBlock callBlk = producerCFG.getBlockForInstruction(callIndex);
            int callLine = callBlk.getAllInstructions().size() - 1;
            IPathInfo enterFromCalleeCopy = newPath.deepCopy();
            enterFromCalleeCopy.setCurrentBlock(callBlk);
            enterFromCalleeCopy.setCurrentLineNum(callLine);
            if (executeToProcedureBoundary(enterFromCalleeCopy, producer)) {
              path.declareFakeWitness();
              return true;
            } // else, refuted
          }
        }
      } else Util.Debug("no feasible path between " + producer + " and " + startNode); // end if feasible path exists
    } // end for each potential producer
    Util.Debug("no producers successful from " + startNode + " at depth " + depth);
    return false;
  }
  
  /**
   * change to add new heuristics for what an abstraction boundary is
   */
  private static boolean isAbstractionBoundary(IMethod method, IPathInfo path) {
    if (method.isPublic()) {
      IPathInfo copy = path.deepCopy();
      copy.removeAllLocalConstraintsFromQuery();
      // don't want to give up witness for free
      return !copy.foundWitness();
    }
    return false;
  }

  /**
   * find possible callers for current path and add a new path for each
   * 
   * @param info
   *          - path that has reached a procedure boundary
   * @return - true if we have found a witness, false otherwise
   */
  @Override
  public boolean handleInterproceduralExecution(IPathInfo path) {
    if (!path.isCallStackEmpty()) {
      // leaving callee and returning to called method
      if (path.returnFromCall())
        addPath(path); // add path if it is feasible
      return false;
    } // else, call stack is empty; we are branching backwards into caller

    // if (isPathInSummary(path)) return false; // summary makes path redundant

    Util.Debug("at function boundary for path " + path.getCurrentNode());

    final CGNode startNode = path.getCurrentNode();
    final IMethod startMethod = startNode.getMethod();

    
    if (startMethod.isClinit()) {
      path.setCurrentNode(WALACFGUtil.getFakeWorldClinitNode(callGraph));
      return handleFakeWorldClinit(path);
    }

    if (isAbstractionBoundary(startMethod, path)) { 
      Util.Debug("at abstraction boundary. going to do piecewise execution");
      addPathAndBranchPlaceholders();
      boolean result = handlePiecewiseExecutionMethodBased(path); 
      Util.Debug("done with piecewise exec; result is " + result);
      if (!result) cleanupPathAndBranchPlaceholders();
      return result;
    } else {
      // method is not public; do normal backward execution
      return super.handleInterproceduralExecution(path);
      // Util.Assert(false, "method " + path.getCurrentNode() +
      // " not at proc boundary");
    }
  }

  public boolean executeToProcedureBoundary(final IPathInfo path, CGNode proc) {
    return executeToProcedureBoundary(path, proc, null, true);
  }

  public boolean executeToProcedureBoundary(final IPathInfo path, CGNode proc, Constraint toProduce) {
    return executeToProcedureBoundary(path, proc, toProduce, true);
  }

  /**
   * execute path and its descendents until we reach an abstraction boundary /
   * find a witness (return true), or refute all paths (return false)
   */
  public boolean executeToProcedureBoundary(final IPathInfo inputPath, CGNode proc, Constraint toProduce, boolean setProc) {
    Util.Pre(!inputPath.isDummy(), "can't execute dummy path!");
    Util.Pre(!inputPath.isLoopMergeIndicator(), "can't execute loop merge indicator!");
    IPathInfo path = inputPath;
    if (setProc) {
      SSACFG.BasicBlock exit = proc.getIR().getControlFlowGraph().exit();
      path.setCurrentNode(proc);
      path.addContextualConstraints(proc); 
      path.setCurrentBlock(exit);
      //path.setCurrentLineNum(exit.getAllInstructions().size() - 1);
      path.setCurrentLineNum(exit.getAllInstructions().size());
    }
    while (true) { // until we're done exploring this call
      Util.Assert(path.isFeasible(), "shouldn't execute infeasible path " + path);
      if (this.executeBackwardsPathIntraprocedural(path)) {
        if (path.foundWitness()) {
          Util.Debug("found witness during piecewise execution! returning");
          // make sure caller knows we found a witness
          inputPath.declareFakeWitness(); 
          return true; // found witness
        } // else, procedure boundary has been reached
        // returned from call, but not back to top-level call chain
        if (!path.isCallStackEmpty()) { 
          // return from the class init or current method
          if (!path.returnFromCall()) {
            // infeasible after return; set path to next path
            path = getNextPath();
            if (path == null) return false;
          }
          Util.Debug("call stack not empty; continuing to execute");
          continue; // keep executing until call stack empty
        }

        /*
        // call stack is now empty
        if (proc.getMethod().isInit()) { 
          // special case for constructors; need to fake returning from call
          Util.Debug("found init " + proc + "; faking return");
          // the reason for this is that we set uninitialized vars to their
          // default values at the beginning of a constructor;
          // this is done in the returnFromCall() method
          Iterator<CGNode> nodeIter = callGraph.getPredNodes(proc);
          Util.Assert(nodeIter.hasNext(), "no callers!");
          CGNode caller = nodeIter.next(); // pick any caller; doesn't matter
          path.setCurrentNode(caller);
          // pick any call site
          Iterator<CallSiteReference> iter = caller.iterateCallSites();
          Util.Assert(iter.hasNext(), "no call sites");
          CallSiteReference site = iter.next();
          SSAInstruction[] instrs = caller.getIR().getInstructions();
          SSAInvokeInstruction call = (SSAInvokeInstruction) instrs[caller.getIR().
                               getCallInstructionIndices(site).intIterator().next()]; // pick any call
          if (!path.simulateQueryReturnFromCall(call, proc)) { // refuted
            path = getNextPath();
            if (path == null) return false;
          }
          if (path.foundWitness()) {
            // make sure caller knows we found a witness
            inputPath.declareFakeWitness();
            return true;
          }

          if (path.foundWitness()) {
            // make sure caller knows we found a witness
            inputPath.declareFakeWitness(); 
            return true;
          }
          // else, executing the class initializer yielded neither witness nor
          // refutation
        }
        */
        Util.Debug("reached function boundary with empty call stack on path " + path);
        /*
        // we have reached the function boundary with an empty call stack, but
        // still have constraints left to produce (no witness)
        // IMPORTANT! otherwise we might not match toProduce. toProduce will
        // contains only heap locations in its constraints, but
        // path may contain local constraints which we need to replace with
        // their corresponding heap locations
        path.removeAllLocalConstraintsFromQuery();
        Util.Debug("after removing all local " + path);
        if (path.foundWitness()) {
          Util.Debug("found witness after returning locals!");
          // make sure caller knows we found a witness
          inputPath.declareFakeWitness(); 
          return true;
        }
        if (toProduce != null) {
          // constraint was not produced if path still contains it
          if (path.containsConstraint(toProduce)) { 
            // constraint was not produced, and thus this method is not a
            // producer (on this path)
            Util.Debug(toProduce + " not produced! refuted.");
            path = getNextPath();
            if (path == null) return false;
            continue;
          }
        }
        */
        path.removeAllLocalConstraintsFromQuery();
        Util.Debug("after removing all local " + path);
        if (path.foundWitness()) {
          Util.Debug("found witness after returning locals!");
          // make sure caller knows we found a witness
          inputPath.declareFakeWitness(); 
          return true;
        }
        
        if (path.getCurrentNode().getMethod().isClinit()) {
          path.setCurrentNode(WALACFGUtil.getFakeWorldClinitNode(callGraph));
          boolean result = handleFakeWorldClinit(path);
          if (result) {
            inputPath.declareFakeWitness();
            return result;
          } // else, path refuted
          path = getNextPath();
          if (path == null) return false;
          continue;
        }

        if (isAbstractionBoundary(path.getCurrentNode().getMethod(), path)) {
          Util.Debug("starting piecewise exec ANEW from " + path.getCurrentNode() + " " + count++);
          addPathAndBranchPlaceholders();
          if (handlePiecewiseExecutionMethodBased(path)) {
            inputPath.declareFakeWitness();
            Util.Debug("result true; returning!");
            return true;
          }
          // else, path refuted
          cleanupPathAndBranchPlaceholders();
          path = getNextPath();
          if (path == null) return false;
          continue;
        }

        // else, keep executing until we reach abstraction boundary
        List<IPathInfo> caseSplits = propagateBackwardToCallers(path);
        if (caseSplits == null) return true;
        for (IPathInfo caseSplit : caseSplits) {
          if (executeToProcedureBoundary(caseSplit, caseSplit.getCurrentNode(), toProduce, false)) return true;
        }
        return false;
      } else { // path refuted
        Util.Debug("visit turned up false");
        path = getNextPath();
        if (path == null) return false;
      }
    }
  }

  int count = 0;

  private IPathInfo getNextPath() {
    IPathInfo path = null;
    if (!pathsToExplore.peek().isDummy()) { // any paths left to explore?
      //path = selectPath(); // pathsToExplore.pop(); // explore next path
      path = selectNonDummyPath();
    } else if (!branchPointStack.peek().isDummy()) { // any branch points left to explore?
      path = mergeNextBranchPoint(); // explore next path
    } else { // both dummies. no paths to explore or branches to merge; refuted.
      Util.Debug("piecewise refuted!");
    }
    
    Util.Debug("getting next path; got " + path);
    if (path != null && path.isDummy()) {
      // got dummy path; oops! replace it and return null
      this.pathsToExplore.addFirst(path);
      if (branchPointStack.peek().isDummy()) return null;
      return getNextPath();
    }
    Util.Assert(path == null || (!path.isLoopMergeIndicator() && !path.isDummy()));
    return path;
  }

  private void handleCallCase(CGNode startNode) {
    Iterator<CGNode> callers = callGraph.getPredNodes(startNode);
    while (callers.hasNext()) {
      CGNode caller = callers.next();
      Util.Debug("caller is " + caller);

      // check if any single caller calls startNode multiple times such that one
      // call site is reachable from the other
      Iterator<CallSiteReference> sites = callGraph.getPossibleSites(caller, startNode);
      Util.Assert(sites.hasNext(), "no site for " + startNode + " in " + caller);
      // the blocks that call our method
      List<ISSABasicBlock> callBlks = new LinkedList<ISSABasicBlock>();
      while (sites.hasNext()) { // for each call site of startNode in caller
        CallSiteReference site = sites.next();
        ISSABasicBlock[] blks = caller.getIR().getBasicBlocksForCall(site);
        Util.Assert(blks.length == 1, "should only ever be one here!");
        callBlks.add(blks[0]);
      }
      SSACFG cfg = caller.getIR().getControlFlowGraph();
      Util.Debug("cfg is " + caller.getIR());
      int cur = 0; // counter to preclude self-comparison
      // for each pair of calls, is one reachable from the other?
      for (ISSABasicBlock callBlk0 : callBlks) {
        Util.Debug("call blk0 " + callBlk0);
        int skip = 0;
        for (ISSABasicBlock callBlk1 : callBlks) {
          Util.Debug("call blk1 " + callBlk1);
          if (skip++ == cur) continue;
          Util.Debug("checking for path from " + callBlk0 + " to " + callBlk1);
          BFSPathFinder<ISSABasicBlock> finder = new BFSPathFinder<ISSABasicBlock>(cfg, callBlk0, callBlk1);
          if (finder.find() != null) { // a path exists
            Util.Assert(false, "a path exists from " + startNode + " to itself in caller " + caller);
          }
        }
        cur++;
      }
      // no path exists in any single caller; need to take wider view
    }
  }

}
