FROM docker.elastic.co/elasticsearch/elasticsearch:8.13.4
RUN bin/elasticsearch-plugin install --batch https://get.infini.cloud/elasticsearch/analysis-ik/8.13.4 \
 && bin/elasticsearch-plugin install --batch https://get.infini.cloud/elasticsearch/analysis-pinyin/8.13.4
