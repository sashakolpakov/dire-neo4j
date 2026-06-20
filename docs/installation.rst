Installation
============

What Gets Installed
-------------------

``dire-neo4j`` installs as a Neo4j server plugin. The jar contains both the
stored procedures and the unmanaged ``/dire/`` viewer.

Supported plugin targets:

* Neo4j ``5.26.27``
* Neo4j ``2026.05.0``

Use the jar that matches the Neo4j server line exactly.

Installed procedures:

* ``dire.layout.write``
* ``dire.layout.stream``
* ``dire.layout.stats``
* ``dire.layout.estimate``

The viewer is not a Neo4j Browser extension. It is served by Neo4j at
``/dire/`` after the unmanaged extension is enabled.

Install From A Release
----------------------

Download the jar for your Neo4j line from GitHub Releases:
https://github.com/sashakolpakov/dire-neo4j/releases. Release assets are named
with both the plugin and Neo4j versions, for example:

.. code-block:: text

   dire-neo4j-plugin-0.1.0-neo4j-5.26.27.jar

Release builds cover Neo4j 5.26.27 and 2026.05.0. Use the artifact whose
Neo4j version matches the server line exactly.

Use this with a self-managed Neo4j server that allows custom server plugins.
Managed Neo4j services such as Aura do not allow installing arbitrary plugin
jars.

Build From Source
-----------------

Java 21 is recommended for current Neo4j releases.

.. code-block:: sh

   export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
   export PATH="$JAVA_HOME/bin:$PATH"

   mvn -Pneo4j-5.26 test
   mvn -Pneo4j-5.26 package

   # Or build against Neo4j 2026.05:
   mvn -Pneo4j-2026.05 package

The plugin jar is:

.. code-block:: text

   neo4j-plugin/target/dire-neo4j-plugin-0.1.0-SNAPSHOT.jar

Install In Neo4j
----------------

Stop Neo4j before replacing plugin jars.

.. code-block:: sh

   cp dire-neo4j-plugin-0.1.0-neo4j-5.26.27.jar "$NEO4J_HOME/plugins/dire-neo4j-plugin.jar"

For a local build, copy:

.. code-block:: sh

   cp neo4j-plugin/target/dire-neo4j-plugin-0.1.0-SNAPSHOT.jar \
     "$NEO4J_HOME/plugins/dire-neo4j-plugin.jar"

Add to ``neo4j.conf``:

.. code-block:: properties

   dbms.security.procedures.unrestricted=dire.*
   dbms.security.procedures.allowlist=dire.*
   server.unmanaged_extension_classes=org.dire.neo4j.plugin=/dire

Restart Neo4j.

Homebrew Neo4j
--------------

.. code-block:: sh

   brew install openjdk@21 neo4j

   export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
   export PATH="$JAVA_HOME/bin:$PATH"

   cp dire-neo4j-plugin-0.1.0-neo4j-5.26.27.jar \
     "$(brew --prefix neo4j)/libexec/plugins/dire-neo4j-plugin.jar"

Edit:

.. code-block:: text

   $(brew --prefix neo4j)/libexec/conf/neo4j.conf

Start Neo4j:

.. code-block:: sh

   NEO4J_CONF="$(brew --prefix neo4j)/libexec/conf" neo4j console

Docker
------

.. code-block:: sh

   docker run --rm \
     --name dire-neo4j \
     -p 7474:7474 -p 7687:7687 \
     -v "$PWD/dire-neo4j-plugin-0.1.0-neo4j-5.26.27.jar:/plugins/dire-neo4j-plugin.jar:ro" \
     -e NEO4J_AUTH=neo4j/password \
     -e 'NEO4J_dbms_security_procedures_unrestricted=dire.*' \
     -e 'NEO4J_dbms_security_procedures_allowlist=dire.*' \
     -e 'NEO4J_server_unmanaged__extension__classes=org.dire.neo4j.plugin=/dire' \
     neo4j:5.26.27

The same command works with a locally built jar if you replace the mounted jar
path with ``neo4j-plugin/target/dire-neo4j-plugin-0.1.0-SNAPSHOT.jar``.

Verify
------

In Neo4j Browser or ``cypher-shell``:

.. code-block:: cypher

   SHOW PROCEDURES
   YIELD name
   WHERE name STARTS WITH 'dire.'
   RETURN name
   ORDER BY name;

Expected:

.. code-block:: text

   dire.layout.estimate
   dire.layout.stats
   dire.layout.stream
   dire.layout.write

Open the viewer:

.. code-block:: text

   http://localhost:7474/dire/

Quick Start
-----------

After installation:

1. Load or keep your graph in Neo4j.
2. Run ``CALL dire.layout.write(...)``.
3. Open ``/dire/`` on the Neo4j HTTP port.

The node projection must return ``id``. The relationship projection must return
``source`` and ``target``. Optional ``weight`` values must be finite and
non-negative.
