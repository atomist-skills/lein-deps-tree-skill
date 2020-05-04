dir=$(realpath .)
version=$(grep "image:" ./atomist.yaml | cut -d ':' -f3)
image=$(grep "image:" ./atomist.yaml | cut -d ':' -f2)
cd ../package-cljs-skill && lein run -m atomist.functions.bootstrap "$API_KEY_STAGING" staging "$image:$version" "$version" "$dir"
cd "$dir" || exit 1
