.. _usertopologies:

Manager Network Topology Definitions (``user_topology.py``)
==================================================================

Custom network topologies are specified as Python snippets that construct a
tree. You can see examples of these in :gh-file-ref:`deploy/runtools/user_topology.py`,
shown below. Better documentation of this API will be available once it stabilizes.

Fundamentally, you create a list of roots, which consists of switch or server
nodes, then construct a tree by adding downlinks to these roots. Since links
are bi-directional, adding a downlink from node A to node B implicitly adds
an uplink from B to A.

You can add additional topology generation methods here, then use them in
``config_runtime.yaml``.

``user_topology.py`` contents:
--------------------------------

.. include:: /../deploy/runtools/user_topology.py
   :code: python


