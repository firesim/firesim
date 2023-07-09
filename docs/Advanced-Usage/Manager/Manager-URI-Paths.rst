.. _uri-path-support:

Manager URI Paths
===============================

Some keys specified in ``config_hwdb.yaml`` may be specified as a ``URI``

``URI Support``
--------------------------
A Uniform Resource Identifier (URI) which specifies a protocol supported either `directly by the fsspec library <https://filesystem-spec.readthedocs.io/en/latest/api.html#built-in-implementations>`_ or by `one of the many third party extension libraries which build on fsspec. <https://filesystem-spec.readthedocs.io/en/latest/api.html#other-known-implementations>`_

Please note that while use use the ``fsspec`` library to handle many different URI protocols, many
of them require additional dependencies that FireSim itself does not require you to install.
``fsspec`` will throw an exception telling you to install missing packages if you use one of the
many URI protocols we do not test.

Likewise, individual URI protocols will have their own requirements for specifying credentials.
Documentation supplying credentials is provided by the individual protocol implementation.  For
example:

* `adlfs for Azure Data-Lake Gen1 and Gen2 <https://github.com/fsspec/adlfs#details>`_
* `gcfs for Google Cloud Services <https://gcsfs.readthedocs.io/en/latest/#credentials>`_
* `s3fs for AWS S3 <https://s3fs.readthedocs.io/en/latest/#credentials>`_

For SSH, add any required keys to your ssh-agent.

Please note that while some protocol backendss provide authentication via their own configuration
files or environment variables (e.g. AWS credentials stored in ``~/.aws``, created by ``aws
configure``), one can additionally configure ``fsspec`` with additional default keyword arguments
per backend protocol by using one of the `fsspec configuration
<https://filesystem-spec.readthedocs.io/en/latest/features.html#configuration>`_ methods.


