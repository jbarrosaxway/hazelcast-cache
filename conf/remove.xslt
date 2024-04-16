<?xml version="1.0" ?>
<stylesheet xmlns="http://www.w3.org/1999/XSL/Transform" version="1.0" xmlns:es="http://www.vordel.com/2005/06/24/entityStore">
  <template match="comment()|processing-instruction()"><copy /></template>
  <template match="@*|node()"><copy><apply-templates select="@*|node()" /></copy></template>
  <!-- Removing type and instances -->
  <template match="/es:entityStoreData/es:entityType[@name='HazelcastCacheManagerLoadableModule']"/>
  <template match="/es:entityStoreData/es:entity[@type='HazelcastCacheManagerLoadableModule']"/>
</stylesheet>
