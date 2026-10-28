# Garage setup, run by the garage-setup Job (sh with curl; no jq). Safe to run again: each step checks first.
# The admin API is HTTPS (the ghostunnel sidecar), checked against the internal CA in $CA.
set -eu
api() { # api METHOD ENDPOINT [JSON]
  if [ $# -gt 2 ]; then
    curl -sS --cacert "$CA" -o /tmp/out -w '%{http_code}' -X "$1" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$3" "$GARAGE/v2/$2"
  else
    curl -sS --cacert "$CA" -o /tmp/out -w '%{http_code}' -X "$1" -H "Authorization: Bearer $TOKEN" "$GARAGE/v2/$2"
  fi
}
field() { sed -n "s/.*\"$1\": *\"\{0,1\}\([^\",]*\)\"\{0,1\},\{0,1\}$/\1/p" /tmp/out | head -n 1; }

echo "waiting for $NODES Garage nodes"
until [ "$(api GET GetClusterStatus)" = 200 ] && [ "$(grep -c '"isUp": true' /tmp/out)" -ge "$NODES" ]; do sleep 3; done

# every node without a role gets one; its zone is its own name, so the copies of an object land on different nodes
roles=""
current=""
while IFS= read -r line; do
  case "$line" in
    *'"id": "'*) current=$(echo "$line" | sed 's/.*"id": "\([0-9a-f]*\)".*/\1/') ;;
    *'"hostname": "'*) host=$(echo "$line" | sed 's/.*"hostname": "\([^"]*\)".*/\1/') ;;
    *'"role": null'*) roles="$roles{\"id\":\"$current\",\"zone\":\"$host\",\"capacity\":$CAPACITY,\"tags\":[]}," ;;
  esac
done < /tmp/out
if [ -n "$roles" ]; then
  echo "assigning roles"
  [ "$(api POST UpdateClusterLayout "{\"roles\":[${roles%,}]}")" = 200 ] || { cat /tmp/out; exit 1; }
  api GET GetClusterLayout >/dev/null
  version=$(field version)
  echo "applying layout version $((version + 1))"
  [ "$(api POST ApplyClusterLayout "{\"version\":$((version + 1))}")" = 200 ] || { cat /tmp/out; exit 1; }
fi

until [ "$(api GET GetClusterHealth)" = 200 ] && grep -q '"status": "healthy"' /tmp/out; do
  echo "waiting for a healthy cluster"; sleep 3
done

code=$(api POST CreateBucket "{\"globalAlias\":\"$BUCKET\"}")
case "$code" in 200) echo "created bucket $BUCKET" ;; 409) echo "bucket $BUCKET exists" ;; *) cat /tmp/out; exit 1 ;; esac
api GET "GetBucketInfo?globalAlias=$BUCKET" >/dev/null
bucket_id=$(field id)

if [ "$(api GET "GetKeyInfo?id=$KEY_ID")" != 200 ]; then
  echo "importing fdrop's key"
  [ "$(api POST ImportKey "{\"accessKeyId\":\"$KEY_ID\",\"secretAccessKey\":\"$KEY_SECRET\",\"name\":\"fdrop\"}")" = 200 ] || { cat /tmp/out; exit 1; }
fi
[ "$(api POST AllowBucketKey "{\"bucketId\":\"$bucket_id\",\"accessKeyId\":\"$KEY_ID\",\"permissions\":{\"read\":true,\"write\":true,\"owner\":false}}")" = 200 ] || { cat /tmp/out; exit 1; }
echo "Garage is set up: bucket $BUCKET, key $KEY_ID"
