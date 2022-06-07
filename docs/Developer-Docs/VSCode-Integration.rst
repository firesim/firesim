Visual Studio Code Integration
------------------------------

`VSCode <https://code.visualstudio.com/>`_ is a powerful IDE that can be used to do code and documentation
development across the FireSim repository. It supports a client-server protocol
over SSH that enables you to run a local GUI client that interacts with a server
running on your remote manager.


General Setup
=============

#. Install VSCode. You can grab installers `here <https://code.visualstudio.com/download>`_.
#. Open VSCode and install the ``Remote Developer Plugin``. See the `marketplace page <https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.vscode-remote-extensionpack>`_ for a complete description of its features.

At this point, VSCode will read in your ``.ssh/config``. Hosts you've listed
there will be listed under the ``Remote Explorer`` in the left sidebar. You'll
be able to connect to these hosts and create workspaces under FireSim clones
you've created there. You may need to give explicit names to hosts that would
otherwise be captured as part of a pattern match or glob in your ssh config.

Workspace Locations
===================

Certain plugins assume the presence of certain files in particular locations,
and often it is diserable to reduce the scope of files that VSCode will index.
We recommend opening workspaces at the following locations:

 * Scala and C++ development: ``sim/``
 * RST docs: ``docs/``
 * Manager (python): ``deploy/``

You can always open a workspace at the root of FireSim -- just be cognizant that
certain language-specific plugins (e.g., may not be configured correclty).


Scala Development
=========================

.. warning:: Until Chipyard is bumped, you must add bloop to Chipyard's ``plugins.sbt`` for this to work correctly. See :gh-file-ref:`sim/project/plugins.sbt` and copy the bloop installation into ``target-design/chipyard/project/plugins.sbt``.

VSCode has rich support for Scala development, and the `Metals <https://scalameta.org/metals/docs/editors/vscode/>`_ plugin is really what makes the magic happen.

How To Use (Remote Manager)
###########################

#. If you haven't already, clone FireSim and run ``build-setup.sh`` on your manager.
#. Ensure your manager instance is listed as a host in your ``.ssh/config``. For example:
   ::

    Host ec2-manager
        User centos
        IdentityFile ~/.ssh/<your-firesim.pem>
        Hostname <IP ADDR>
    
#. In VSCode, using the ``Remote Manager`` on the left sidebar, connect to your manager instance.
#. Open a workspace in your FireSim clone under ``sim/``.
#. First time per remote: install the Metals plugin on the *remote* machine.
#. Metals will prompt you with the following: "New SBT Workspace Detected, would you like to import the build?". Click *Import Build*.


At this point, metals should automatically attempt to import the SBT-defined build rooted at ``sim/``. It will:

#. Call out to SBT to run ``bloopInstall``
#. Spin up a bloop build server. 
#. Compile all scala sources for the default SBT project in firesim. 

Once this process is complete, autocompletion, jump to source, code lenses, and all that good stuff should work correctly.

Limitations
###########

#. **No test task support for ScalaTests that use make.** Due to the way
   FireSim's ScalaTest calls out to make to invoke the generator and Golden Gate, Metals's bloop instance
   must initialized with ``env.sh`` sourced. This will be resolved in a future PR. 

Other Notes
###########

Reliance on SBT multi-project builds breaks the default metals integration. To hide this, we've put workspace-specific settings for metals in
:gh-file-ref:`sim/.vscode/settings.json` which should permit metals to run correctly out of
``sim/``. This instructs metals that:

#. We've already installed bloop (by listing it as a plugin in FireSim and Chipyard).
#. It should use a different sbt launch command to run ``bloopInstall``. This
   sources ``env.sh`` and uses the sbt-launcher provided by Chipyard.
