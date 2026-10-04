package lk.routerank.scoring;

/** Where published files go: R2 in production, a map in tests. */
interface ObjectStore {

	/** No R2 settings: the job scores but publishes nothing. */
	ObjectStore NONE = new ObjectStore() {

		@Override
		public void put(String key, byte[] body, String contentType, String cacheControl) {
			throw new UnsupportedOperationException("publishing is off");
		}

		@Override
		public void delete(String key) {
			throw new UnsupportedOperationException("publishing is off");
		}

	};

	void put(String key, byte[] body, String contentType, String cacheControl);

	void delete(String key);

}
