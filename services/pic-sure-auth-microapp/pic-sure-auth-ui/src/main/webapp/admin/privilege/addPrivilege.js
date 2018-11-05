define(["backbone", "handlebars", "picSure/privilegeFunctions", "privilege/privilegeManagement", "text!privilege/addPrivilege.hbs", "text!privilege/addPrivilegeConnectionForm.hbs", "picSure/privilegeFunctions"],
		function(BB, HBS, privilegeFunctions, privilegeManagement, template, connectionTemplate, privilegeFunctions){
	var view = BB.View.extend({
		initialize: function(opts){
			this.privileges = opts.privileges;
			this.template = HBS.compile(template);
			this.managementConsole = opts.managementConsole;


		},
		events: {
			// "change #new-privilege-connection-dropdown":"renderConnectionForm",
			"click #save-privilege-button": "createPrivilege"
		},
		createPrivilege: function(event){
			var metadata = {};
			// _.each($('#current-connection-form input[type=text]'), function(entry){
			// metadata[entry.name] = entry.value});
			var privilege = {
				uuid : $('#new-privilege-form input[name=privilege_name]').attr('uuid'),
				name : $('#new-privilege-form input[name=privilege_name]').val(),
				description : $('#new-privilege-form input[name=privilege_description]').val()
				// connectionId: $('#new-privilege-connection-dropdown').val(),
				// generalMetadata:JSON.stringify(metadata),
				// privileges: _.pluck(this.$('input:checked'),'value').join(',')
			};
			privilegeFunctions.createOrUpdatePrivilege(
				[privilege],
				"POST",
				function(result){
					console.log(result);
                    this.managementConsole.render();
				}.bind(this)
			);
		},
		// renderConnectionForm: function(event){
		// 	this.connection = _.find(this.connections, {id:event.target.value});
		// 	privilegeFunctions.getAvailablePrivileges(function (privileges) {
		// 		$('#current-connection-form', this.$el).html(
		// 				this.connectionTemplate({
		// 						connection: this.connection,
		// 						createOrUpdatePrivilege: true,
		// 						availablePrivileges: privileges
		// 				}));
         //    }.bind(this));
		// },
		render: function(){
			this.$el.html(this.template({privileges:this.privileges}));
			// this.renderConnectionForm({target:{value:this.connections[0].id}})
		}
	});
	return view;
});