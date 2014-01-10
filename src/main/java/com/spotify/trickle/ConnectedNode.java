package com.spotify.trickle;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureFallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.builder;
import static com.google.common.util.concurrent.Futures.allAsList;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;

/**
 * Represents a node that has been connected to its input dependencies.
 */
class ConnectedNode {
  private final String name;
  private final TrickleNode node;
  private final ImmutableList<Dep<?>> inputs;
  private final ImmutableList<Node<?>> predecessors;
  private final Optional<?> defaultValue;

  ConnectedNode(String name, Node<?> node, Iterable<Dep<?>> inputs, List<Node<?>> predecessors, Optional<?> defaultValue) {
    this.name = checkNotNull(name, "name");
    this.node = TrickleNode.create(node);
    this.defaultValue = checkNotNull(defaultValue, "defaultValue");
    this.predecessors = ImmutableList.copyOf(predecessors);
    this.inputs = ImmutableList.copyOf(inputs);
  }

  ListenableFuture<Object> future(
      final Map<Name<?>, Object> bindings,
      final Map<Node<?>, ConnectedNode> nodes,
      final Map<Node<?>, ListenableFuture<?>> visited,
      Executor executor) {
    checkNotNull(bindings, "bindings");
    checkNotNull(nodes, "nodes");
    checkNotNull(visited, "visited");
    checkNotNull(executor, "executor");

    // filter out future and value dependencies
    final ImmutableList.Builder<ListenableFuture<?>> futuresListBuilder = builder();

    for (Dep<?> input : inputs) {
      // TODO: convert to using polymorphism?!
      // depends on other node
      if (input instanceof NodeDep) {
        final Node<?> inputNode = ((NodeDep) input).getNode();

        final ListenableFuture<?> future = futureForNode(bindings, nodes, visited, inputNode, executor);

        futuresListBuilder.add(future);

        // depends on bind
      } else if (input instanceof BindingDep) {
        final BindingDep<?> bindingDep = (BindingDep<?>) input;
        final Object bindingValue = bindings.get(bindingDep.getName());

        checkArgument(bindingValue != null,
            "Name not bound to a value for name %s, of type %s",
            bindingDep.getName(), bindingDep.getCls());

        checkArgument(bindingDep.getCls().isAssignableFrom(bindingValue.getClass()),
            "Binding type mismatch, expected %s, found %s",
            bindingDep.getCls(), bindingValue.getClass());

        if (bindingValue instanceof ListenableFuture) {
          futuresListBuilder.add((ListenableFuture<?>) bindingValue);
        }
        else {
          futuresListBuilder.add(immediateFuture(bindingValue));
        }
      } else {
        throw new IllegalStateException("PROGRAMMER ERROR: unsupported Dep: " + input);
      }
    }

    final ImmutableList<ListenableFuture<?>> futures = futuresListBuilder.build();

    // future for signaling propagation - needs to include predecessors, too
    List<ListenableFuture<?>> mustHappenBefore = Lists.newArrayList(futures);
    for (Node<?> predecessor : predecessors) {
      mustHappenBefore.add(futureForNode(bindings, nodes, visited, predecessor, executor));
    }

    final ListenableFuture<List<Object>> allFuture = allAsList(mustHappenBefore);

    checkArgument(inputs.size() == futures.size(), "sanity check result: insane");

    return Futures.withFallback(nodeFuture(futures, allFuture, executor), new FutureFallback<Object>() {
      @Override
      public ListenableFuture<Object> create(Throwable t) {
        if (defaultValue.isPresent()) {
          return immediateFuture(defaultValue.get());
        }

        return immediateFailedFuture(t);
      }
    });
  }

  private ListenableFuture<Object> nodeFuture(final ImmutableList<ListenableFuture<?>> values, ListenableFuture<List<Object>> doneSignal, Executor executor) {
    return Futures.transform(
        doneSignal,
        new AsyncFunction<List<Object>, Object>() {
          @Override
          public ListenableFuture<Object> apply(List<Object> input) {
            return node.run(Lists.transform(values, new Function<ListenableFuture<?>, Object>() {
              @Override
              public Object apply(ListenableFuture<?> input) {
                return Futures.getUnchecked(input);
              }
            }));
          }
        },
        executor);
  }

  private ListenableFuture<?> futureForNode(Map<Name<?>, Object> bindings, Map<Node<?>, ConnectedNode> nodes, Map<Node<?>, ListenableFuture<?>> visited, Node<?> node, Executor executor) {
    final ListenableFuture<?> future;
    if (visited.containsKey(node)) {
      future = visited.get(node);
    } else {
      future = nodes.get(node).future(bindings, nodes, visited, executor);
      visited.put(node, future);
    }
    return future;
  }

  String getName() {
    return name;
  }

  ImmutableList<Dep<?>> getInputs() {
    return inputs;
  }

  ImmutableList<Node<?>> getPredecessors() {
    return predecessors;
  }

  @Override
  public String toString() {
    return name;
  }
}
