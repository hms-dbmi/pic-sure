define([], function(){
	// TODO : This will be integrated with a backend service when one exists.
	var connections =  [
		{
			label:"BCH", 
			id:"ldap-connector",
			subPrefix:"ldap-connector|", 
			requiredFields:[{label:"BCH Email", id:"BCHEmail"}],
			optionalFields:[{label:"BCH ID", id:"BCHId"}]
		}
//		,{
//			label:"HMS", 
//			id:"hms-it",
//			subPrefix:"samlp|", 
//			requiredFields:[{label:"HMS Email", id:"HMSEmail"}]
//		}
	];
	return connections;
});