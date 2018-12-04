define(["backbone", "handlebars", "picSure/applicationFunctions", "application/applicationManagement", "text!application/addApplication.hbs", "text!application/addApplicationConnectionForm.hbs", "picSure/applicationFunctions"],
		function(BB, HBS, applicationFunctions, applicationManagement, template, connectionTemplate, applicationFunctions){
	var view = BB.View.extend({
		initialize: function(opts){
			this.applications = opts.applications;
			this.template = HBS.compile(template);
			this.managementConsole = opts.managementConsole;


		},
		events: {
			"click #save-application-button": "createApplication"
		},
		createApplication: function(event){
			var metadata = {};
			var application = {
				uuid : $('#new-application-form input[name=application_name]').attr('uuid'),
				name : $('#new-application-form input[name=application_name]').val(),
				description : $('#new-application-form input[name=application_description]').val()
			};
			applicationFunctions.createOrUpdateApplication(
				[application],
				"POST",
				function(result){
					console.log(result);
                    this.managementConsole.render();
				}.bind(this)
			);
		},
		render: function(){
			this.$el.html(this.template({applications:this.applications}));
		}
	});
	return view;
});